package dev.comfyfluffy.caustica.rt.entity;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for first-person state providers with deterministic selection and circuit-breaker semantics.
 * <p>
 * Thread-safe for registration (can be called during mod init). Selection happens on render thread only.
 */
public final class FirstPersonStateRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(FirstPersonStateRegistry.class);
    private static final FirstPersonStateRegistry INSTANCE = new FirstPersonStateRegistry();

    public static FirstPersonStateRegistry instance() {
        return INSTANCE;
    }

    private final Map<String, ProviderEntry> providers = new ConcurrentHashMap<>();
    private final Set<String> circuitBroken = new HashSet<>();
    private final Set<String> warnedOwnershipProviders = new HashSet<>();
    private boolean warnedTie = false;

    private FirstPersonStateRegistry() {
    }

    /**
     * Registers a first-person state provider.
     *
     * @param id unique provider identifier
     * @param priority integer priority (higher = preferred)
     * @param provider the state provider
     * @param safety camera safety declaration (may be same object as provider)
     */
    public void register(String id, int priority, FirstPersonStateProvider provider, CameraSafetyDeclaration safety) {
        if (id == null || provider == null || safety == null) {
            throw new IllegalArgumentException("Provider ID, provider, and safety must not be null");
        }
        providers.put(id, new ProviderEntry(priority, provider, safety));
        LOGGER.debug("Registered first-person provider '{}' with priority {}", id, priority);
    }

    /**
     * Selects the provider with the highest priority. Returns null if no providers registered,
     * multiple providers tie for max priority, or all providers are circuit-broken.
     *
     * @return selected provider entry, or null
     */
    @Nullable
    public SelectedProvider selectProvider() {
        if (providers.isEmpty()) {
            return null;
        }

        // Find max priority among non-circuit-broken providers
        int maxPriority = Integer.MIN_VALUE;
        String maxId = null;
        ProviderEntry maxEntry = null;
        int countAtMax = 0;

        for (Map.Entry<String, ProviderEntry> entry : providers.entrySet()) {
            String id = entry.getKey();
            if (circuitBroken.contains(id)) {
                continue;
            }
            ProviderEntry pe = entry.getValue();
            // maxEntry guards the first candidate: a provider whose priority is exactly Integer.MIN_VALUE
            // would otherwise never win the `>` comparison against the initial sentinel.
            if (maxEntry == null || pe.priority > maxPriority) {
                maxPriority = pe.priority;
                maxId = id;
                maxEntry = pe;
                countAtMax = 1;
            } else if (pe.priority == maxPriority) {
                countAtMax++;
            }
        }

        if (maxId == null) {
            // All providers circuit-broken or none available
            return null;
        }

        if (countAtMax > 1) {
            // Tie: log once per session
            if (!warnedTie) {
                LOGGER.warn("Multiple first-person providers tied at priority {}; refusing to select. " +
                        "Assign distinct priorities to resolve.", maxPriority);
                warnedTie = true;
            }
            return null;
        }

        return new SelectedProvider(maxId, maxEntry.provider, maxEntry.safety);
    }

    /**
     * Marks a provider as circuit-broken for the remainder of this session.
     *
     * @param id provider identifier
     * @param cause the exception that triggered the circuit break
     */
    public void circuitBreak(String id, Throwable cause) {
        if (circuitBroken.add(id)) {
            LOGGER.warn("First-person provider '{}' circuit-broken due to exception; " +
                    "will not be selected for remainder of session", id, cause);
        }
    }

    /**
     * Reports a state whose vanilla entity id does not belong to the camera entity. Warned at most once
     * per provider per session; the provider stays selectable because a mismatch is a per-frame condition
     * rather than a structural failure.
     */
    public void warnOwnershipMismatch(String id, int expectedEntityId, int actualEntityId) {
        if (warnedOwnershipProviders.add(id)) {
            LOGGER.warn("First-person provider '{}' returned a state owned by entity {} but the camera "
                    + "entity is {}; discarding the first-person instance", id, actualEntityId, expectedEntityId);
        }
    }

    public static final class SelectedProvider {
        public final String id;
        public final FirstPersonStateProvider provider;
        public final CameraSafetyDeclaration safety;

        SelectedProvider(String id, FirstPersonStateProvider provider, CameraSafetyDeclaration safety) {
            this.id = id;
            this.provider = provider;
            this.safety = safety;
        }
    }

    private static final class ProviderEntry {
        final int priority;
        final FirstPersonStateProvider provider;
        final CameraSafetyDeclaration safety;

        ProviderEntry(int priority, FirstPersonStateProvider provider, CameraSafetyDeclaration safety) {
            this.priority = priority;
            this.provider = provider;
            this.safety = safety;
        }
    }
}
