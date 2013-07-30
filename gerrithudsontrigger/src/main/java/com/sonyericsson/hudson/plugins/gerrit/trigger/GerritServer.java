/*
 *  The MIT License
 *
 *  Copyright 2010 Sony Ericsson Mobile Communications. All rights reserved.
 *  Copyright 2012 Sony Mobile Communications AB. All rights reserved.
 *  Copyright 2013 Ericsson.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package com.sonyericsson.hudson.plugins.gerrit.trigger;

import hudson.model.AbstractProject;
import hudson.model.Hudson;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sonyericsson.hudson.plugins.gerrit.gerritevents.ConnectionListener;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.GerritEventListener;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.GerritHandler;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.GerritSendCommandQueue;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.dto.GerritEvent;
import com.sonyericsson.hudson.plugins.gerrit.trigger.config.Config;
import com.sonyericsson.hudson.plugins.gerrit.trigger.config.IGerritHudsonTriggerConfig;
import com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritConnectionListener;
import com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritTrigger;

/**
 * Every instance of this class represents a Gerrit server having its own unique name,
 * event manager, project list updater, configuration, and lists of listeners.
 * All interactions with a Gerrit server should go through this class.
 * The list of GerritServer is kept in @PluginImpl.
 *
 * @author Robert Sandell &lt;robert.sandell@sonyericsson.com&gt;
 * @author Mathieu Wang &lt;mathieu.wang@ericsson.com&gt;
 *
 */
public class GerritServer {

    private static final Logger logger = LoggerFactory.getLogger(GerritServer.class);
    private String name;
    private transient GerritHandler gerritEventManager;
    private transient GerritProjectListUpdater projectListUpdater;
    private IGerritHudsonTriggerConfig config;
    private transient Collection<GerritEventListener> savedEventListeners;
    private transient Collection<ConnectionListener> savedConnectionListeners;
    private transient GerritConnectionListener gerritConnectionListener;

    /**
     * Constructor.
     *
     * @param name the name of the server.
     */
    public GerritServer(String name) {
        this.name = name;
        config = new Config();
    }

    /**
     * Gets the global config of this server.
     *
     * @return the config.
     */
    public IGerritHudsonTriggerConfig getConfig() {
        return config;
    }

    /**
     * Sets the global config of this server.
     *
     * @param config the config.
     */
    public void setConfig(IGerritHudsonTriggerConfig config) {
        this.config = config;
    }

    /**
     * Get the name of the server.
     *
     * @return name the name of the server.
     */
    public String getName() {
        return name;
    }

    /**
     * Return the list of jobs configured with this server.
     * Used for blocking removal of a server from the list when some jobs still have listeners for that server.
     *
     * @return the list of jobs configured with this server.
     */
    public List<AbstractProject> getConfiguredJobs() {
        ArrayList<AbstractProject> configuredJobs = new ArrayList<AbstractProject>();
        for (AbstractProject<?, ?> project : Hudson.getInstance().getItems(AbstractProject.class)) { //get the jobs
            GerritTrigger gerritTrigger = project.getTrigger(GerritTrigger.class);

            //if the job has a gerrit trigger, check whether the trigger has selected this server:
            if (gerritTrigger != null && gerritTrigger.getServerName().equals(name)) {
                configuredJobs.add(project); //job has selected this server, add it to the list
            }
        }
        return configuredJobs;
    }

    /**
     * Starts the server's project list updater, send command queue and event manager.
     *
     */
    public void start() {
        logger.info("Starting GerritServer: " + name);
        projectListUpdater = new GerritProjectListUpdater(name);
        projectListUpdater.start();

        //Starts the send-command-queue
        GerritSendCommandQueue.getInstance(config);

        //do not try to connect to gerrit unless there is a URL or a hostname in the text fields
        List<VerdictCategory> categories = config.getCategories();
        if (categories == null) {
            categories = new LinkedList<VerdictCategory>();
        }
        if (categories.isEmpty()) {
            categories.add(new VerdictCategory("CRVW", "Code Review"));
            categories.add(new VerdictCategory("VRIF", "Verified"));
        }
        config.setCategories(categories);

        initializeConnectionListener();

        logger.info(name + " started");
    }

    /**
     * Initializes the Gerrit connection listener for this server.
     * Add it to the list of connection listeners if not in saved listeners.
     *
     */
    private void initializeConnectionListener() {
        gerritConnectionListener = new GerritConnectionListener(name);
        boolean listenerSaved = savedConnectionListeners != null
                && savedConnectionListeners.contains(gerritConnectionListener);

        if (!listenerSaved) {
            boolean connected = addListener(gerritConnectionListener);
            gerritConnectionListener.setConnected(connected);
            gerritConnectionListener.checkGerritVersionFeatures();
        }
    }

    /**
     * Stops the server's project list updater, send command queue and event manager.
     *
     */
    public void stop() {
        logger.info("Stopping GerritServer " + name);
        projectListUpdater.shutdown();
        try {
            projectListUpdater.join();
        } catch (InterruptedException ie) {
            logger.error("project list updater of " + name + "interrupted", ie);
        }
        if (gerritEventManager != null) {
            gerritEventManager.shutdown(false);
            //TODO save to registered listeners?
            gerritEventManager = null;
        }
        GerritSendCommandQueue.shutdown();
        logger.info(name + " stopped");
    }

    /**
     * Creates the GerritEventManager
     */
    private void createManager() {
        gerritEventManager = new GerritHandler(name, config);

        //Add any event/connectionlisteners that were created while the connection was down.
        if (savedConnectionListeners != null) {
            gerritEventManager.addConnectionListeners(savedConnectionListeners);
            savedConnectionListeners = null;
        }
        if (savedEventListeners != null) {
            gerritEventManager.addEventListeners(savedEventListeners);
            savedEventListeners = null;
        }
    }

    /**
     * Adds a listener to the EventManager.  The listener will receive all events from Gerrit.
     *
     * @param listener the listener to add.
     * @see GerritHandler#addListener(com.sonyericsson.hudson.plugins.gerrit.gerritevents.GerritEventListener)
     */
    public void addListener(GerritEventListener listener) {
        if (gerritEventManager != null) {
            gerritEventManager.addListener(listener);
        } else {
            //If the eventmanager isn't created yet, save the eventlistener so it can be added once
            //the eventmanager is created.
            if (savedEventListeners == null) {
                savedEventListeners = Collections.synchronizedSet(new HashSet<GerritEventListener>());
            }
            savedEventListeners.add(listener);
        }
    }

    /**
     * Removes a listener from the manager.
     *
     * @param listener the listener to remove.
     * @see GerritHandler#removeListener(com.sonyericsson.hudson.plugins.gerrit.gerritevents.GerritEventListener)
     */
    public void removeListener(GerritEventListener listener) {
        if (gerritEventManager != null) {
            gerritEventManager.removeListener(listener);
        } else {
            if (savedEventListeners != null) {
                savedEventListeners.remove(listener);
            }
        }
    }

    /**
     * Removes a connection listener from the manager.
     *
     * @param listener the listener to remove.
     */
    public void removeListener(ConnectionListener listener) {
        if (gerritEventManager != null) {
            gerritEventManager.removeListener(listener);
        } else {
            if (savedConnectionListeners != null) {
                savedConnectionListeners.remove(listener);
            }
        }
    }

    /**
     * Get the GerritConnectionListener for GerritAdministrativeMonitor.
     * @return the GerritConnectionListener, or null if it has not yet been initialized.
     */
    public GerritConnectionListener getGerritConnectionListener() {
        return gerritConnectionListener;
    }

    /**
     * Starts the connection to Gerrit stream of events.
     *
     * @see GerritManagement.DescriptorImpl#doStartConnection()
     */
    public synchronized void startConnection() {
        if (!config.hasDefaultValues()) {
            if (gerritEventManager == null) {
                createManager();
                gerritEventManager.start();
            } else {
                logger.warn("Already started!");
            }
        }
    }

    /**
     * Stops the connection to Gerrit stream of events.
     *
     * @see GerritManagement.DescriptorImpl#doStopConnection()
     */
    public synchronized void stopConnection() {
        if (gerritEventManager != null) {
            gerritEventManager.shutdown(true);

            savedEventListeners = gerritEventManager.removeAllEventListeners();
            savedConnectionListeners = gerritEventManager.removeAllConnectionListeners();
            gerritEventManager = null;
        } else {
            logger.warn("Was told to shutdown again?");
        }
    }

    /**
     * Restarts the connection to Gerrit stream of events.
     *
     * @see GerritManagement.DescriptorImpl#doRestartConnection()
     */
    public void restartConnection() {
        stopConnection();
        startConnection();
    }

    /**
     * Adds a Connection Listener to the manager.
     * Return the current connection status so that listeners that
     * are added later than a connectionestablished/ connectiondown
     * will get the current connection status.
     *
     * @param listener the listener to be added.
     * @return the connection status, ie., whether we are connected to the server or not.
     */
    public boolean addListener(ConnectionListener listener) {
        boolean connected = false;
        if (gerritEventManager != null) {
            connected = gerritEventManager.addListener(listener);
        } else {
            //If the eventmanager isn't created yet, save the connectionlistener so it can be added once
            //the eventmanager is created.
            if (savedConnectionListeners == null) {
                savedConnectionListeners = Collections.synchronizedSet(new HashSet<ConnectionListener>());
            }
            savedConnectionListeners.add(listener);
        }
        return connected;
    }

    /**
     * Returns a list of Gerrit projects.
     *
     * @return list of gerrit projects
     */
    public List<String> getGerritProjects() {
        if (projectListUpdater != null) {
            return projectListUpdater.getGerritProjects();
        } else {
            return new ArrayList<String>();
        }
    }

    /**
     * Adds the given event to the stream of events.
     * It gets added to the same event queue as any event coming from the stream-events command in Gerrit.
     * Throws IllegalStateException if the event manager is null
     *
     * @param event the event.
     * @see GerritHandler#triggerEvent(com.sonyericsson.hudson.plugins.gerrit.gerritevents.dto.GerritEvent)
     */
    public void triggerEvent(GerritEvent event) {
        if (gerritEventManager != null) {
            gerritEventManager.triggerEvent(event);
        } else {
            throw new IllegalStateException("Manager not started!");
        }
    }

    /**
     * Returns the current Gerrit version.
     *
     * @return the current Gerrit version as a String if connected, or null otherwise.
     */
    public String getGerritVersion() {
        if (gerritEventManager != null) {
            return gerritEventManager.getGerritVersion();
        } else {
            return null;
        }
    }
}
