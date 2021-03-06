package org.ray.runtime.gcs;

import com.google.common.base.Preconditions;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.ray.api.Checkpointable.Checkpoint;
import org.ray.api.id.BaseId;
import org.ray.api.id.TaskId;
import org.ray.api.id.UniqueId;
import org.ray.api.runtimecontext.NodeInfo;
import org.ray.runtime.generated.Gcs.ActorCheckpointIdData;
import org.ray.runtime.generated.Gcs.ClientTableData;
import org.ray.runtime.generated.Gcs.ClientTableData.EntryType;
import org.ray.runtime.generated.Gcs.TablePrefix;
import org.ray.runtime.util.IdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of GcsClient.
 */
public class GcsClient {

  private static Logger LOGGER = LoggerFactory.getLogger(GcsClient.class);

  private RedisClient primary;

  private List<RedisClient> shards;

  public GcsClient(String redisAddress, String redisPassword) {
    primary = new RedisClient(redisAddress, redisPassword);
    int numShards = 0;
    try {
      numShards = Integer.valueOf(primary.get("NumRedisShards", null));
      Preconditions.checkState(numShards > 0,
          String.format("Expected at least one Redis shards, found %d.", numShards));
    } catch (NumberFormatException e) {
      throw new RuntimeException("Failed to get number of redis shards.", e);
    }

    List<byte[]> shardAddresses = primary.lrange("RedisShards".getBytes(), 0, -1);
    Preconditions.checkState(shardAddresses.size() == numShards);
    shards = shardAddresses.stream().map((byte[] address) -> {
      return new RedisClient(new String(address));
    }).collect(Collectors.toList());
  }

  public List<NodeInfo> getAllNodeInfo() {
    final String prefix = TablePrefix.CLIENT.toString();
    final byte[] key = ArrayUtils.addAll(prefix.getBytes(), UniqueId.NIL.getBytes());
    List<byte[]> results = primary.lrange(key, 0, -1);

    if (results == null) {
      return new ArrayList<>();
    }

    // This map is used for deduplication of client entries.
    Map<UniqueId, NodeInfo> clients = new HashMap<>();
    for (byte[] result : results) {
      Preconditions.checkNotNull(result);
      ClientTableData data = null;
      try {
        data = ClientTableData.parseFrom(result);
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException("Received invalid protobuf data from GCS.");
      }
      final UniqueId clientId = UniqueId
          .fromByteBuffer(data.getClientId().asReadOnlyByteBuffer());

      if (data.getEntryType() == EntryType.INSERTION) {
        //Code path of node insertion.
        Map<String, Double> resources = new HashMap<>();
        // Compute resources.
        Preconditions.checkState(
            data.getResourcesTotalLabelCount() == data.getResourcesTotalCapacityCount());
        for (int i = 0; i < data.getResourcesTotalLabelCount(); i++) {
          resources.put(data.getResourcesTotalLabel(i), data.getResourcesTotalCapacity(i));
        }
        NodeInfo nodeInfo = new NodeInfo(
            clientId, data.getNodeManagerAddress(), true, resources);
        clients.put(clientId, nodeInfo);
      } else if (data.getEntryType() == EntryType.RES_CREATEUPDATE) {
        Preconditions.checkState(clients.containsKey(clientId));
        NodeInfo nodeInfo = clients.get(clientId);
        for (int i = 0; i < data.getResourcesTotalLabelCount(); i++) {
          nodeInfo.resources.put(data.getResourcesTotalLabel(i), data.getResourcesTotalCapacity(i));
        }
      } else if (data.getEntryType() == EntryType.RES_DELETE) {
        Preconditions.checkState(clients.containsKey(clientId));
        NodeInfo nodeInfo = clients.get(clientId);
        for (int i = 0; i < data.getResourcesTotalLabelCount(); i++) {
          nodeInfo.resources.remove(data.getResourcesTotalLabel(i));
        }
      } else {
        // Code path of node deletion.
        Preconditions.checkState(data.getEntryType() == EntryType.DELETION);
        NodeInfo nodeInfo = new NodeInfo(clientId, clients.get(clientId).nodeAddress,
            false, clients.get(clientId).resources);
        clients.put(clientId, nodeInfo);
      }
    }

    return new ArrayList<>(clients.values());
  }

  /**
   * If the actor exists in GCS.
   */
  public boolean actorExists(UniqueId actorId) {
    byte[] key = ArrayUtils.addAll(
        TablePrefix.ACTOR.toString().getBytes(), actorId.getBytes());
    return primary.exists(key);
  }

  /**
   * Query whether the raylet task exists in Gcs.
   */
  public boolean rayletTaskExistsInGcs(TaskId taskId) {
    byte[] key = ArrayUtils.addAll(TablePrefix.RAYLET_TASK.toString().getBytes(),
        taskId.getBytes());
    RedisClient client = getShardClient(taskId);
    return client.exists(key);
  }

  /**
   * Get the available checkpoints for the given actor ID.
   */
  public List<Checkpoint> getCheckpointsForActor(UniqueId actorId) {
    List<Checkpoint> checkpoints = new ArrayList<>();
    final String prefix = TablePrefix.ACTOR_CHECKPOINT_ID.toString();
    final byte[] key = ArrayUtils.addAll(prefix.getBytes(), actorId.getBytes());
    RedisClient client = getShardClient(actorId);

    byte[] result = client.get(key);
    if (result != null) {
      ActorCheckpointIdData data = null;
      try {
        data = ActorCheckpointIdData.parseFrom(result);
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException("Received invalid protobuf data from GCS.");
      }
      UniqueId[] checkpointIds = new UniqueId[data.getCheckpointIdsCount()];
      for (int i = 0; i < checkpointIds.length; i++) {
        checkpointIds[i] = UniqueId
            .fromByteBuffer(data.getCheckpointIds(i).asReadOnlyByteBuffer());
      }

      for (int i = 0; i < checkpointIds.length; i++) {
        checkpoints.add(new Checkpoint(checkpointIds[i], data.getTimestamps(i)));
      }
    }
    checkpoints.sort((x, y) -> Long.compare(y.timestamp, x.timestamp));
    return checkpoints;
  }

  private RedisClient getShardClient(BaseId key) {
    return shards.get((int) Long.remainderUnsigned(IdUtil.murmurHashCode(key),
        shards.size()));
  }

}
