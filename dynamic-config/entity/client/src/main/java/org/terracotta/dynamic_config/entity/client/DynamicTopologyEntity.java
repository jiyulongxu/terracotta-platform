/*
 * Copyright (c) 2011-2019 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA, and/or its subsidiaries and/or its affiliates and/or their licensors.
 * Use, reproduction, transfer, publication or disclosure is prohibited except as specifically provided for in your License Agreement with Software AG.
 */
package org.terracotta.dynamic_config.entity.client;

import org.terracotta.connection.entity.Entity;
import org.terracotta.dynamic_config.api.model.Cluster;
import org.terracotta.dynamic_config.api.model.Configuration;
import org.terracotta.dynamic_config.api.model.License;
import org.terracotta.dynamic_config.api.model.Node;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * @author Mathieu Carbou
 */
public interface DynamicTopologyEntity extends Entity {

  /**
   * Set the listener which will receive server events
   */
  void setListener(Listener listener);

  /**
   * Returns the topology that has been lastly persisted in the config repository and will be
   * effective after a restart if the node needs to be restarted following a change
   * <p>
   * If a configuration change is made, and this change does not require a restart, the change will be persisted in the config repository,
   * and the change will be directly applied to both the runtime topology and the upcoming one, so that they are equal.
   */
  Cluster getUpcomingCluster() throws TimeoutException, InterruptedException;

  /**
   * Returns the topology that is currently in effect at runtime.
   * <p>
   * This topology could be equal to the upcoming one in case a change can be applied at runtime
   * or when the node has just been started and no configuration change has been made
   */
  Cluster getRuntimeCluster() throws TimeoutException, InterruptedException;

  /**
   * @return true if some dynamic changes have been done which cannot be applied at runtime and need a restart to be applied
   */
  boolean mustBeRestarted() throws TimeoutException, InterruptedException;

  /**
   * @return true if a configuration as been prepared on this node, but it has not yet been committed or rolled back.
   * In this state, the nodes are currently within a Nomad transaction, or, a partial commit/rollback occurred and the node
   * needs a configuration repair
   */
  boolean hasIncompleteChange() throws TimeoutException, InterruptedException;

  /**
   * Get the current installed license information if any.
   * <p>
   * Can return null.
   */
  License getLicense() throws TimeoutException, InterruptedException;

  class Settings {
    private Duration requestTimeout = Duration.ofSeconds(20);

    public Duration getRequestTimeout() {
      return requestTimeout;
    }

    public Settings setRequestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }
  }

  interface Listener {
    default void onNodeRemoval(int stripeId, Node removedNode) {}

    default void onNodeAddition(int stripeId, Node addedNode) {}

    default void onSettingChange(Configuration configuration, Cluster cluster) {}

    default void onDisconnected() {}
  }
}
