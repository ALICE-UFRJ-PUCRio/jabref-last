package org.jabref.logic.autosaveandbackup;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.jabref.logic.util.CoarseChangeFilter;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.database.event.AutosaveEvent;
import org.jabref.model.database.event.BibDatabaseContextChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Saves the given {@link BibDatabaseContext} on every {@link BibDatabaseContextChangedEvent} by posting a new {@link AutosaveEvent}.
 *  * An intelligent {@link ExecutorService} with a {@link BlockingQueue} prevents a high load while saving and rejects all redundant save tasks.
 *  */
public class AutosaveManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutosaveManager.class);

    private static final int DELAY_BETWEEN_AUTOSAVE_ATTEMPTS_IN_SECONDS = 31;

    private static Set<AutosaveManager> runningInstances = new HashSet<>();

    private final BibDatabaseContext bibDatabaseContext;

    private final EventBus eventBus;
    private final CoarseChangeFilter changeFilter;
    private final BlockingQueue<Runnable> workerQueue;
    private final ExecutorService executor;
    private boolean needsSave = false;

    private AutosaveManager(BibDatabaseContext bibDatabaseContext) {
        this.bibDatabaseContext = bibDatabaseContext;
        this.eventBus = new EventBus();
        this.changeFilter = new CoarseChangeFilter(bibDatabaseContext);
        changeFilter.registerListener(this);

        this.workerQueue = new ArrayBlockingQueue<>(1);
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, workerQueue);
    }

    @Subscribe
    public void listen(@SuppressWarnings("unused") BibDatabaseContextChangedEvent event) {
        try {
            executor.submit(() -> {
                eventBus.post(new AutosaveEvent());
            });
        } catch (RejectedExecutionException e) {
            LOGGER.debug("Rejecting autosave while another save process is already running.");
        }
    }

    private void shutdown() {
        changeFilter.unregisterListener(this);
        changeFilter.shutdown();
        executor.shutdown();
    }

    /**
     * Starts the Autosaver which is associated with the given {@link BibDatabaseContext}.
     *
     * @param bibDatabaseContext Associated {@link BibDatabaseContext}
     */
    public static AutosaveManager start(BibDatabaseContext bibDatabaseContext) {
        AutosaveManager autosaveManager = new AutosaveManager(bibDatabaseContext);
        runningInstances.add(autosaveManager);
        return autosaveManager;
    }

    /**
     * Shuts down the Autosaver which is associated with the given {@link BibDatabaseContext}.
     *
     * @param bibDatabaseContext Associated {@link BibDatabaseContext}
     */
    public static void shutdown(BibDatabaseContext bibDatabaseContext) {
        runningInstances.stream().filter(instance -> instance.bibDatabaseContext == bibDatabaseContext).findAny()
                        .ifPresent(instance -> {
                            instance.shutdown();
                            runningInstances.remove(instance);
                        });
    }

    public void registerListener(Object listener) {
        eventBus.register(listener);
    }

    public void unregisterListener(Object listener) {
        try {
            eventBus.unregister(listener);
        } catch (IllegalArgumentException e) {
            // occurs if the event source has not been registered, should not prevent shutdown
            LOGGER.debug("Problem unregistering", e);
        }
    }
}
