/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.dynamic_config.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.dynamic_config.api.model.Cluster;
import org.terracotta.dynamic_config.api.model.License;
import org.terracotta.dynamic_config.api.model.Node;
import org.terracotta.dynamic_config.api.model.NodeContext;
import org.terracotta.dynamic_config.api.model.Stripe;
import org.terracotta.dynamic_config.api.model.nomad.DynamicConfigNomadChange;
import org.terracotta.dynamic_config.api.model.nomad.MultiSettingNomadChange;
import org.terracotta.dynamic_config.api.model.nomad.SettingNomadChange;
import org.terracotta.dynamic_config.api.service.ClusterValidator;
import org.terracotta.dynamic_config.api.service.DynamicConfigEventService;
import org.terracotta.dynamic_config.api.service.DynamicConfigListener;
import org.terracotta.dynamic_config.api.service.DynamicConfigService;
import org.terracotta.dynamic_config.api.service.EventRegistration;
import org.terracotta.dynamic_config.api.service.LicenseParser;
import org.terracotta.dynamic_config.api.service.TopologyService;
import org.terracotta.dynamic_config.server.nomad.NomadBootstrapper;
import org.terracotta.nomad.messages.AcceptRejectResponse;
import org.terracotta.nomad.messages.CommitMessage;
import org.terracotta.nomad.messages.PrepareMessage;
import org.terracotta.nomad.messages.RollbackMessage;
import org.terracotta.nomad.server.NomadChangeInfo;
import org.terracotta.nomad.server.NomadException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.lang.System.lineSeparator;
import static java.util.Objects.requireNonNull;

public class DynamicConfigServiceImpl implements TopologyService, DynamicConfigService, DynamicConfigEventService, DynamicConfigListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(DynamicConfigServiceImpl.class);
  private static final String LICENSE_FILE_NAME = "license.xml";

  private final LicenseParser licenseParser;
  private final NomadBootstrapper.NomadServerManager nomadServerManager;
  private final TcServer tcServer;
  private final List<DynamicConfigListener> listeners = new CopyOnWriteArrayList<>();

  private volatile NodeContext upcomingNodeContext;
  private volatile NodeContext runtimeNodeContext;
  private volatile License license;
  private volatile boolean clusterActivated;

  public DynamicConfigServiceImpl(NodeContext nodeContext, LicenseParser licenseParser, NomadBootstrapper.NomadServerManager nomadServerManager, TcServer tcServer) {
    this.upcomingNodeContext = requireNonNull(nodeContext);
    this.runtimeNodeContext = requireNonNull(nodeContext);
    this.licenseParser = requireNonNull(licenseParser);
    this.nomadServerManager = requireNonNull(nomadServerManager);
    this.tcServer = tcServer;
    if (loadLicense()) {
      validateAgainstLicense();
    }
  }

  /**
   * called from startup manager (in case we want a pre-activated node) (and this class) to make Nomad RW.
   */
  public synchronized void activate() {
    if (isActivated()) {
      throw new AssertionError("Already activated");
    }
    LOGGER.info("Preparing activation of Node with validated topology: {}", upcomingNodeContext.getCluster().toShapeString());
    nomadServerManager.upgradeForWrite(upcomingNodeContext.getStripeId(), upcomingNodeContext.getNodeName());
    LOGGER.debug("Setting nomad writable successful");

    clusterActivated = true;
    LOGGER.info("Node activation successful");

    if (nomadServerManager.getNomadServer().hasIncompleteChange()) {
      LOGGER.error(lineSeparator() + lineSeparator()
          + "==============================================================================================================================================" + lineSeparator()
          + "The configuration of this node has not been committed or rolled back. Please run the 'diagnostic' command to diagnose the configuration state." + lineSeparator()
          + "==============================================================================================================================================" + lineSeparator()
      );
    }
  }

  // do not move this method up in the interface otherwise any client could access the license content through diagnostic port
  public synchronized Optional<String> getLicenseContent() {
    Path targetLicensePath = nomadServerManager.getRepositoryManager().getLicensePath().resolve(LICENSE_FILE_NAME);
    if (Files.exists(targetLicensePath)) {
      try {
        return Optional.of(new String(Files.readAllBytes(targetLicensePath), StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return Optional.empty();
  }

  @Override
  public EventRegistration register(DynamicConfigListener listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  @Override
  public void onSettingChanged(SettingNomadChange change, Cluster updated) {
    if (change.canApplyAtRuntime()) {
      LOGGER.info("Configuration change: {} applied at runtime", change.getSummary());
    } else {
      LOGGER.info("Configuration change: {} will be applied after restart", change.getSummary());
    }
    // do not fire events within a synchronized block
    listeners.forEach(c -> c.onSettingChanged(change, updated));
  }

  @Override
  public void onNewConfigurationSaved(NodeContext nodeContext, Long version) {
    LOGGER.info("New config repository version: {} has been saved", version);
    // do not fire events within a synchronized block
    NodeContext upcoming = getUpcomingNodeContext();
    listeners.forEach(c -> c.onNewConfigurationSaved(upcoming, version));
  }

  @Override
  public void onNodeRemoval(int stripeId, Node removedNode) {
    InetSocketAddress addr = removedNode.getNodeAddress();
    LOGGER.info("Removed node: {} from stripe ID: {}", addr, stripeId);
    // do not fire events within a synchronized block
    listeners.forEach(c -> c.onNodeRemoval(stripeId, removedNode));
  }

  @Override
  public void onNodeAddition(int stripeId, Node addedNode) {
    LOGGER.info("Added node:{} to stripe ID: {}", addedNode.getNodeAddress(), stripeId);
    // do not fire events within a synchronized block
    listeners.forEach(c -> c.onNodeAddition(stripeId, addedNode));
  }

  @Override
  public void onNomadPrepare(PrepareMessage message, AcceptRejectResponse response) {
    if (response.isAccepted()) {
      LOGGER.info("Nomad change {} prepared: {}", message.getChangeUuid(), message.getChange().getSummary());
    } else {
      LOGGER.warn("Nomad change {} failed to prepare: {}", message.getChangeUuid(), response);
    }
  }

  @Override
  public void onNomadCommit(CommitMessage message, AcceptRejectResponse response, NomadChangeInfo changeInfo) {
    if (response.isAccepted()) {
      DynamicConfigNomadChange dynamicConfigNomadChange = (DynamicConfigNomadChange) changeInfo.getNomadChange();
      LOGGER.info("Nomad change {} committed: {}", message.getChangeUuid(), dynamicConfigNomadChange.getSummary());

      // extract the changes since there can be multiple settings change
      List<? extends DynamicConfigNomadChange> nomadChanges = MultiSettingNomadChange.extractChanges(dynamicConfigNomadChange);

      // the following code will be executed on all the nodes, regardless of the applicability
      // level to update the config
      synchronized (this) {
        for (DynamicConfigNomadChange nomadChange : nomadChanges) {
          // first we update the upcoming one
          Cluster upcomingCluster = nomadChange.apply(upcomingNodeContext.getCluster());
          upcomingNodeContext = upcomingNodeContext.withCluster(upcomingCluster);
          // if the change can be applied at runtime, it was previously done in the config change handler.
          // so update also the runtime topology there
          if (nomadChange.canApplyAtRuntime()) {
            Cluster runtimeCluster = nomadChange.apply(runtimeNodeContext.getCluster());
            runtimeNodeContext = runtimeNodeContext.withCluster(runtimeCluster);
          }
        }
      }
    } else {
      LOGGER.warn("Nomad change {} failed to commit: {}", message.getChangeUuid(), response);
    }

    listeners.forEach(c -> c.onNomadCommit(message, response, changeInfo));
  }

  @Override
  public void onNomadRollback(RollbackMessage message, AcceptRejectResponse response) {
    if (response.isAccepted()) {
      LOGGER.info("Nomad change {} rolled back", message.getChangeUuid());
    } else {
      LOGGER.warn("Nomad change {} failed to rollback: {}", message.getChangeUuid(), response);
    }
  }

  @Override
  public synchronized NodeContext getUpcomingNodeContext() {
    return upcomingNodeContext.clone();
  }

  @Override
  public synchronized NodeContext getRuntimeNodeContext() {
    return runtimeNodeContext.clone();
  }

  @Override
  public void restart(Duration delayInSeconds) {
    // The delay helps the caller close the connection while it's live, otherwise it gets stuck for request timeout duration
    final long millis = delayInSeconds.toMillis();
    if (millis < 1_000) {
      throw new IllegalArgumentException("Invalid delay: " + delayInSeconds.getSeconds() + " seconds");
    }
    LOGGER.info("Node will restart in: {} seconds", delayInSeconds.getSeconds());
    new Thread(getClass().getSimpleName() + "-DelayedRestart") {
      @Override
      public void run() {
        try {
          sleep(millis);
        } catch (InterruptedException e) {
          // do nothing, still try to kill server
        }
        LOGGER.info("Restarting node");
        tcServer.restart();
      }
    }.start();
  }

  @Override
  public boolean isActivated() {
    return clusterActivated;
  }

  @Override
  public synchronized boolean mustBeRestarted() {
    return !runtimeNodeContext.equals(upcomingNodeContext);
  }

  @Override
  public boolean hasIncompleteChange() {
    return nomadServerManager.getNomadServer().hasIncompleteChange();
  }

  @Override
  public synchronized void setUpcomingCluster(Cluster updatedCluster) {
    if (isActivated()) {
      throw new IllegalStateException("Use Nomad instead to change teh topology of activated node: " + runtimeNodeContext.getNode().getNodeAddress());
    }

    requireNonNull(updatedCluster);

    new ClusterValidator(updatedCluster).validate();

    Node oldMe = upcomingNodeContext.getNode();
    Node newMe = findMe(updatedCluster);

    if (newMe != null) {
      // we have updated the topology and I am still part of this cluster
      LOGGER.info("Set upcoming topology to: {}", updatedCluster.toShapeString());
      this.upcomingNodeContext = new NodeContext(updatedCluster, newMe.getNodeAddress());
    } else {
      // We have updated the topology and I am not part anymore of the cluster
      // So we just reset the cluster object so that this node is alone
      LOGGER.info("Node {} ({}) removed from pending topology: {}", oldMe.getNodeName(), oldMe.getNodeAddress(), updatedCluster.toShapeString());
      this.upcomingNodeContext = new NodeContext(new Cluster(new Stripe(oldMe)), oldMe.getNodeAddress());
    }

    // When node is not yet activated, runtimeNodeContext == upcomingNodeContext
    this.runtimeNodeContext = upcomingNodeContext;
  }

  @Override
  public synchronized void activate(Cluster maybeUpdatedCluster, String licenseContent) {
    if (isActivated()) {
      throw new IllegalStateException("Node is already activated");
    }

    LOGGER.info("Preparing activation of cluster: {}", maybeUpdatedCluster.toShapeString());

    // validate that we are part of this cluster
    if (findMe(maybeUpdatedCluster) == null) {
      throw new IllegalArgumentException(String.format(
          "No match found for node: %s in cluster topology: %s",
          upcomingNodeContext.getNodeName(),
          maybeUpdatedCluster
      ));
    }

    this.setUpcomingCluster(maybeUpdatedCluster);
    this.installLicense(licenseContent);

    activate();
  }

  @Override
  public synchronized void upgradeLicense(String licenseContent) {
    this.installLicense(licenseContent);
  }

  @Override
  public synchronized Optional<License> getLicense() {
    return Optional.ofNullable(license);
  }

  @Override
  public NomadChangeInfo[] getChangeHistory() {
    try {
      return nomadServerManager.getNomadServer().getAllNomadChanges().toArray(new NomadChangeInfo[0]);
    } catch (NomadException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public synchronized boolean validateAgainstLicense(Cluster cluster) {
    if (this.license == null) {
      LOGGER.debug("Unable to validate cluster against license: license not installed: {}", cluster.toShapeString());
      return false;
    }
    LicenseValidator licenseValidator = new LicenseValidator(cluster, license);
    licenseValidator.validate();
    LOGGER.debug("License is valid for cluster: {}", cluster.toShapeString());
    return true;
  }

  private synchronized void validateAgainstLicense() {
    validateAgainstLicense(upcomingNodeContext.getCluster());
  }

  private synchronized void installLicense(String licenseContent) {
    Path targetLicensePath = nomadServerManager.getRepositoryManager().getLicensePath().resolve(LICENSE_FILE_NAME);

    if (licenseContent != null) {
      License backup = this.license;
      Path tempFile = null;

      try {
        tempFile = Files.createTempFile("terracotta-license-", ".xml");
        Files.write(tempFile, licenseContent.getBytes(StandardCharsets.UTF_8));
        this.license = licenseParser.parse(tempFile);

        validateAgainstLicense();
        LOGGER.info("License validated");
        moveLicense(targetLicensePath, tempFile);
        LOGGER.info("License installed");
      } catch (IOException e) {
        // rollback to previous license (or null) on IO error
        this.license = backup;
        throw new UncheckedIOException(e);
      } catch (RuntimeException e) {
        // rollback to previous license (or null) on validation error
        this.license = backup;
        throw e;

      } finally {
        if (tempFile != null) {
          try {
            Files.deleteIfExists(tempFile);
          } catch (IOException ignored) {
          }
        }
      }

      LOGGER.info("License installation successful");

    } else {
      LOGGER.info("No license installed");
      this.license = null;
      try {
        Files.deleteIfExists(targetLicensePath);
      } catch (IOException e) {
        LOGGER.warn("Error deleting existing license: " + e.getMessage(), e);
      }
    }
  }

  private void moveLicense(Path destination, Path tempFile) {
    LOGGER.debug("Moving license file: {} to: {}", tempFile, destination);
    try {
      Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private boolean loadLicense() {
    Path licenseFile = nomadServerManager.getRepositoryManager().getLicensePath().resolve(LICENSE_FILE_NAME);
    if (Files.exists(licenseFile)) {
      LOGGER.info("Reloading license");
      this.license = licenseParser.parse(licenseFile);
      return true;
    }
    return false;
  }

  /**
   * Tries to find the node representing this process within the updated cluster.
   * <p>
   * - We cannot use the node hostname or port only, since they might have changed through a set command.
   * - We cannot use the node name and stripe ID only, since the stripe ID can have changed in the new cluster with the attach/detach commands
   * <p>
   * So we try to find the best match we can...
   */
  private synchronized Node findMe(Cluster updatedCluster) {
    final Node me = upcomingNodeContext.getNode();
    return updatedCluster.getNode(me.getNodeInternalAddress()) // important to use the internal address
        .orElseGet(() -> updatedCluster.getNode(upcomingNodeContext.getStripeId(), me.getNodeName())
            .orElse(null));
  }
}