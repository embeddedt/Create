package com.simibubi.create.content.logistics.trains;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.UUID;

import com.simibubi.create.content.logistics.trains.management.edgePoint.EdgePointType;
import com.simibubi.create.content.logistics.trains.management.signal.EdgeData;
import com.simibubi.create.content.logistics.trains.management.signal.TrackEdgePoint;
import com.simibubi.create.foundation.utility.Couple;
import com.simibubi.create.foundation.utility.Pair;
import com.simibubi.create.foundation.utility.VecHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

public class TrackGraphSyncPacket extends TrackGraphPacket {

	Map<Integer, Pair<TrackNodeLocation, Vec3>> addedNodes;
	List<Pair<Couple<Integer>, TrackEdge>> addedEdges;
	List<Integer> removedNodes;
	List<TrackEdgePoint> addedEdgePoints;
	List<UUID> removedEdgePoints;
	Map<Integer, UUID> splitSubGraphs;
	Map<Couple<Integer>, Pair<Integer, List<UUID>>> updatedEdgeData;

	static final int NULL_GROUP = 0, PASSIVE_GROUP = 1, GROUP = 2;

	public TrackGraphSyncPacket(UUID graphId) {
		this.graphId = graphId;
		addedNodes = new HashMap<>();
		addedEdges = new ArrayList<>();
		removedNodes = new ArrayList<>();
		addedEdgePoints = new ArrayList<>();
		removedEdgePoints = new ArrayList<>();
		updatedEdgeData = new HashMap<>();
		splitSubGraphs = new HashMap<>();
		packetDeletesGraph = false;
	}

	public TrackGraphSyncPacket(FriendlyByteBuf buffer) {
		int size;

		graphId = buffer.readUUID();
		packetDeletesGraph = buffer.readBoolean();

		if (packetDeletesGraph)
			return;

		addedNodes = new HashMap<>();
		addedEdges = new ArrayList<>();
		addedEdgePoints = new ArrayList<>();
		removedEdgePoints = new ArrayList<>();
		removedNodes = new ArrayList<>();
		splitSubGraphs = new HashMap<>();
		updatedEdgeData = new HashMap<>();

		size = buffer.readVarInt();
		for (int i = 0; i < size; i++)
			removedNodes.add(buffer.readVarInt());

		size = buffer.readVarInt();
		for (int i = 0; i < size; i++)
			addedNodes.put(buffer.readVarInt(),
				Pair.of(TrackNodeLocation.fromPackedPos(buffer.readBlockPos()), VecHelper.read(buffer)));

		size = buffer.readVarInt();
		for (int i = 0; i < size; i++)
			addedEdges.add(Pair.of(Couple.create(buffer::readVarInt), TrackEdge.read(buffer)));

		size = buffer.readVarInt();
		for (int i = 0; i < size; i++)
			addedEdgePoints.add(EdgePointType.read(buffer));

		size = buffer.readVarInt();
		for (int i = 0; i < size; i++)
			removedEdgePoints.add(buffer.readUUID());

		size = buffer.readVarInt();
		for (int i = 0; i < size; i++) {
			ArrayList<UUID> list = new ArrayList<>();
			Couple<Integer> key = Couple.create(buffer::readInt);
			Pair<Integer, List<UUID>> entry = Pair.of(buffer.readVarInt(), list);
			int size2 = buffer.readVarInt();
			for (int j = 0; j < size2; j++)
				list.add(buffer.readUUID());
			updatedEdgeData.put(key, entry);
		}

		size = buffer.readVarInt();
		for (int i = 0; i < size; i++)
			splitSubGraphs.put(buffer.readVarInt(), buffer.readUUID());
	}

	@Override
	public void write(FriendlyByteBuf buffer) {

		buffer.writeUUID(graphId);
		buffer.writeBoolean(packetDeletesGraph);
		if (packetDeletesGraph)
			return;

		buffer.writeVarInt(removedNodes.size());
		removedNodes.forEach(buffer::writeVarInt);

		buffer.writeVarInt(addedNodes.size());
		addedNodes.forEach((node, loc) -> {
			buffer.writeVarInt(node);
			buffer.writeBlockPos(new BlockPos(loc.getFirst()));
			VecHelper.write(loc.getSecond(), buffer);
		});

		buffer.writeVarInt(addedEdges.size());
		addedEdges.forEach(pair -> {
			pair.getFirst()
				.forEach(buffer::writeVarInt);
			pair.getSecond()
				.write(buffer);
		});

		buffer.writeVarInt(addedEdgePoints.size());
		addedEdgePoints.forEach(ep -> ep.write(buffer));

		buffer.writeVarInt(removedEdgePoints.size());
		removedEdgePoints.forEach(buffer::writeUUID);

		buffer.writeVarInt(updatedEdgeData.size());
		for (Entry<Couple<Integer>, Pair<Integer, List<UUID>>> entry : updatedEdgeData.entrySet()) {
			entry.getKey()
				.forEach(buffer::writeInt);
			Pair<Integer, List<UUID>> pair = entry.getValue();
			buffer.writeVarInt(pair.getFirst());
			List<UUID> list = pair.getSecond();
			buffer.writeVarInt(list.size());
			list.forEach(buffer::writeUUID);
		}

		buffer.writeVarInt(splitSubGraphs.size());
		splitSubGraphs.forEach((node, uuid) -> {
			buffer.writeVarInt(node);
			buffer.writeUUID(uuid);
		});
	}

	@Override
	protected void handle(GlobalRailwayManager manager, TrackGraph graph) {
		if (packetDeletesGraph) {
			manager.removeGraph(graph);
			return;
		}

		for (int nodeId : removedNodes) {
			TrackNode node = graph.getNode(nodeId);
			if (node != null)
				graph.removeNode(null, node.getLocation());
		}

		for (Entry<Integer, Pair<TrackNodeLocation, Vec3>> entry : addedNodes.entrySet()) {
			Integer nodeId = entry.getKey();
			Pair<TrackNodeLocation, Vec3> nodeLocation = entry.getValue();
			graph.loadNode(nodeLocation.getFirst(), nodeId, nodeLocation.getSecond());
		}

		for (Pair<Couple<Integer>, TrackEdge> pair : addedEdges) {
			Couple<TrackNode> nodes = pair.getFirst()
				.map(graph::getNode);
			if (nodes.getFirst() != null && nodes.getSecond() != null)
				graph.putConnection(nodes.getFirst(), nodes.getSecond(), pair.getSecond());
		}

		for (TrackEdgePoint edgePoint : addedEdgePoints)
			graph.edgePoints.put(edgePoint.getType(), edgePoint);

		for (UUID uuid : removedEdgePoints)
			for (EdgePointType<?> type : EdgePointType.TYPES.values())
				graph.edgePoints.remove(type, uuid);

		handleEdgeData(manager, graph);

		if (!splitSubGraphs.isEmpty())
			graph.findDisconnectedGraphs(splitSubGraphs)
				.forEach(manager::putGraph);
	}

	protected void handleEdgeData(GlobalRailwayManager manager, TrackGraph graph) {
		for (Entry<Couple<Integer>, Pair<Integer, List<UUID>>> entry : updatedEdgeData.entrySet()) {
			List<UUID> idList = entry.getValue()
				.getSecond();
			int groupType = entry.getValue()
				.getFirst();

			Couple<TrackNode> nodes = entry.getKey()
				.map(graph::getNode);
			if (nodes.either(Objects::isNull))
				continue;
			TrackEdge edge = graph.getConnectionsFrom(nodes.getFirst())
				.get(nodes.getSecond());
			if (edge == null)
				continue;

			EdgeData edgeData = new EdgeData();
			if (groupType == NULL_GROUP)
				edgeData.singleSignalGroup = null;
			else if (groupType == PASSIVE_GROUP)
				edgeData.singleSignalGroup = EdgeData.passiveGroup;
			else
				edgeData.singleSignalGroup = idList.get(0);

			List<TrackEdgePoint> points = edgeData.getPoints();
			edge.edgeData = edgeData;

			for (int i = groupType == GROUP ? 1 : 0; i < idList.size(); i++) {
				UUID uuid = idList.get(i);
				for (EdgePointType<?> type : EdgePointType.TYPES.values()) {
					TrackEdgePoint point = graph.edgePoints.get(type, uuid);
					if (point == null)
						continue;
					points.add(point);
					break;
				}
			}
		}
	}

	public void syncEdgeData(TrackNode node1, TrackNode node2, TrackEdge edge) {
		Couple<Integer> key = Couple.create(node1.getNetId(), node2.getNetId());
		List<UUID> list = new ArrayList<>();
		EdgeData edgeData = edge.getEdgeData();
		int groupType = edgeData.hasSignalBoundaries() ? NULL_GROUP
			: EdgeData.passiveGroup.equals(edgeData.singleSignalGroup) ? PASSIVE_GROUP : GROUP;
		if (groupType == GROUP)
			list.add(edgeData.singleSignalGroup);
		for (TrackEdgePoint point : edgeData.getPoints())
			list.add(point.getId());
		updatedEdgeData.put(key, Pair.of(groupType, list));
	}

}