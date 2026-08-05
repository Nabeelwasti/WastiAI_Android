package com.example.data.memory.graph

import com.example.data.memory.model.GraphEdge
import com.example.data.memory.model.GraphNode
import com.example.data.memory.model.KnowledgeGraph
import com.example.data.memory.model.NodeType
import com.example.data.memory.model.RelationType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class KnowledgeGraphEngine {
    private val nodes = ConcurrentHashMap<String, GraphNode>()
    private val edges = ConcurrentHashMap<String, GraphEdge>()

    fun addNode(node: GraphNode) {
        nodes[node.id] = node
    }

    fun addEdge(sourceId: String, targetId: String, relation: RelationType, weight: Float = 1.0f): GraphEdge {
        val edgeId = "edge-${UUID.randomUUID()}"
        val edge = GraphEdge(
            id = edgeId,
            sourceNodeId = sourceId,
            targetNodeId = targetId,
            relation = relation,
            weight = weight
        )
        edges[edgeId] = edge
        return edge
    }

    fun getNode(id: String): GraphNode? = nodes[id]

    fun findNodesByType(type: NodeType): List<GraphNode> {
        return nodes.values.filter { it.type == type }
    }

    fun getConnectedNodes(nodeId: String): List<Pair<GraphNode, RelationType>> {
        val connected = mutableListOf<Pair<GraphNode, RelationType>>()
        edges.values.forEach { edge ->
            if (edge.sourceNodeId == nodeId) {
                nodes[edge.targetNodeId]?.let { connected.add(Pair(it, edge.relation)) }
            } else if (edge.targetNodeId == nodeId) {
                nodes[edge.sourceNodeId]?.let { connected.add(Pair(it, edge.relation)) }
            }
        }
        return connected
    }

    fun getGraphSnapshot(): KnowledgeGraph {
        return KnowledgeGraph(
            nodes = nodes.values.toList(),
            edges = edges.values.toList()
        )
    }

    fun getGraphSummary(): String {
        val nodeCount = nodes.size
        val edgeCount = edges.size
        if (nodeCount == 0) return "Knowledge graph empty."
        
        val summaryLines = nodes.values.take(15).map { node ->
            val rels = getConnectedNodes(node.id).joinToString(", ") { "${it.second.name} -> ${it.first.label}" }
            "- [${node.type.name}] ${node.label} ${if (rels.isNotBlank()) "($rels)" else ""}"
        }
        return "Knowledge Graph ($nodeCount nodes, $edgeCount edges):\n" + summaryLines.joinToString("\n")
    }
}
