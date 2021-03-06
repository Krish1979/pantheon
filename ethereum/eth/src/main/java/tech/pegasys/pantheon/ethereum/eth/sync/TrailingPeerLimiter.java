/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.eth.sync;

import static org.apache.logging.log4j.LogManager.getLogger;

import tech.pegasys.pantheon.ethereum.chain.BlockAddedEvent;
import tech.pegasys.pantheon.ethereum.chain.BlockAddedObserver;
import tech.pegasys.pantheon.ethereum.chain.Blockchain;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeer;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeers;
import tech.pegasys.pantheon.ethereum.p2p.wire.messages.DisconnectMessage.DisconnectReason;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Logger;

public class TrailingPeerLimiter implements BlockAddedObserver {

  private static final Logger LOG = getLogger();

  private static final Comparator<EthPeer> BY_CHAIN_HEIGHT =
      Comparator.comparing(peer -> peer.chainState().getEstimatedHeight());
  // Note rechecking only on blocks that are a multiple of 100 is just a simple way of limiting
  // how often we rerun the check.
  private static final int RECHECK_PEERS_WHEN_BLOCK_NUMBER_MULTIPLE_OF = 100;
  private final EthPeers ethPeers;
  private final Supplier<TrailingPeerRequirements> trailingPeerRequirementsCalculator;

  public TrailingPeerLimiter(
      final EthPeers ethPeers,
      final Supplier<TrailingPeerRequirements> trailingPeerRequirementsCalculator) {
    this.ethPeers = ethPeers;
    this.trailingPeerRequirementsCalculator = trailingPeerRequirementsCalculator;
  }

  public void enforceTrailingPeerLimit() {
    final TrailingPeerRequirements requirements = trailingPeerRequirementsCalculator.get();
    if (requirements.getMaxTrailingPeers() == Long.MAX_VALUE) {
      return;
    }
    final long minimumHeightToBeUpToDate = requirements.getMinimumHeightToBeUpToDate();
    final long maxTrailingPeers = requirements.getMaxTrailingPeers();
    final List<EthPeer> trailingPeers =
        ethPeers
            .availablePeers()
            .filter(peer -> peer.chainState().hasEstimatedHeight())
            .filter(peer -> peer.chainState().getEstimatedHeight() < minimumHeightToBeUpToDate)
            .sorted(BY_CHAIN_HEIGHT)
            .collect(Collectors.toList());

    while (!trailingPeers.isEmpty() && trailingPeers.size() > maxTrailingPeers) {
      final EthPeer peerToDisconnect = trailingPeers.remove(0);
      LOG.debug("Enforcing trailing peers limit by disconnecting {}", peerToDisconnect);
      peerToDisconnect.disconnect(DisconnectReason.TOO_MANY_PEERS);
    }
  }

  @Override
  public void onBlockAdded(final BlockAddedEvent event, final Blockchain blockchain) {
    if (event.isNewCanonicalHead()
        && event.getBlock().getHeader().getNumber() % RECHECK_PEERS_WHEN_BLOCK_NUMBER_MULTIPLE_OF
            == 0) {
      enforceTrailingPeerLimit();
    }
  }
}
