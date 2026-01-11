package com.secrux.service.enrichment

import com.secrux.domain.Finding
import com.secrux.dto.CodeSnippetDto
import com.secrux.dto.DataFlowEdgeDto
import com.secrux.dto.DataFlowNodeDto
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import org.springframework.stereotype.Component

@Component
class JavaCodeEnricher(
    private val clock: Clock
) : CodeEnricher {

    override fun supports(path: String): Boolean = path.endsWith(".java", ignoreCase = true)

    override fun enrich(
        finding: Finding,
        snippet: CodeSnippetDto?,
        dataFlowNodes: List<DataFlowNodeDto>,
        dataFlowEdges: List<DataFlowEdgeDto>,
    ): Map<String, Any?>? {
        val primaryPathRaw = finding.location["path"]?.toString() ?: return null
        val primaryLine = extractLine(finding.location) ?: return null
        val primaryPath = Path.of(primaryPathRaw)
        if (!Files.exists(primaryPath) || !Files.isRegularFile(primaryPath)) {
            return null
        }

        val maxFiles = 6
        val maxNodes = 10
        val maxMethodChars = 25_000
        val maxCallSamples = 80
        val maxConditions = 40
        val maxExternalSymbols = 40
        val neighborhoodWindowLines = 25
        val maxNeighborhoodConditions = 12
        val maxNeighborhoodInvocations = 20
        val maxNeighborhoodFieldDefs = 12

        val nodeLocations =
            dataFlowNodes
                .asSequence()
                .mapNotNull { node ->
                    val file = node.file?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val line = node.line ?: return@mapNotNull null
                    NodeLocation(nodeId = node.id, label = node.label, path = file, line = line)
                }
                .distinctBy { "${it.path}:${it.line}:${it.nodeId}" }
                .take(maxNodes)
                .toList()

        val filesToParse =
            (nodeLocations.map { it.path } + primaryPathRaw)
                .distinct()
                .take(maxFiles)

        val indexes =
            filesToParse.mapNotNull { path ->
                val p = Path.of(path)
                if (!Files.exists(p) || !Files.isRegularFile(p)) return@mapNotNull null
                runCatching { JavaAstIndex.get(p) }.getOrNull()
            }

        val primaryIndex =
            indexes.firstOrNull { it.path.toString() == primaryPathRaw }
                ?: runCatching { JavaAstIndex.get(primaryPath) }.getOrNull()
        val primaryMethod = primaryIndex?.findEnclosingMethod(primaryLine)

        val dataflowMethods =
            nodeLocations.mapNotNull { loc ->
                val idx = indexes.firstOrNull { it.path.toString() == loc.path } ?: return@mapNotNull null
                val m = idx.findEnclosingMethod(loc.line) ?: return@mapNotNull null
                val neighborhood = idx.buildNeighborhood(method = m.tree, focusLine = loc.line, window = neighborhoodWindowLines)
                val neighborhoodSymbols =
                    neighborhood.externalSymbols
                        .asSequence()
                        .map { normalizeJavaSymbol(it) }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(maxExternalSymbols)
                        .toList()
                val neighborhoodFieldDefs =
                    if (neighborhoodSymbols.isNotEmpty()) {
                        idx.findFieldDefinitionsAround(m.tree, neighborhoodSymbols).take(maxNeighborhoodFieldDefs)
                    } else {
                        emptyList()
                    }
                mapOf(
                    "nodeId" to loc.nodeId,
                    "label" to loc.label,
                    "path" to idx.path.toString(),
                    "line" to loc.line,
                    "method" to m.toSummary(maxChars = maxMethodChars),
                    "neighborhood" to mapOf(
                        "windowLines" to neighborhoodWindowLines,
                        "conditions" to neighborhood.conditions.take(maxNeighborhoodConditions),
                        "invocations" to neighborhood.invocations.take(maxNeighborhoodInvocations),
                        "externalSymbols" to neighborhoodSymbols,
                        "fieldDefinitions" to neighborhoodFieldDefs,
                    ),
                )
            }

        val primarySummary = primaryMethod?.toSummary(maxChars = maxMethodChars)

        val nodeLineSetByFile = nodeLocations.groupBy { it.path }.mapValues { (_, list) -> list.map { it.line }.toSet() }

        val primaryInvocations =
            if (primaryIndex != null && primaryMethod != null) {
                val invocations = primaryIndex.collectInvocations(primaryMethod.tree)
                val fileNodeLines = nodeLineSetByFile[primaryIndex.path.toString()].orEmpty()
                invocations.take(maxCallSamples).map { inv ->
                    mapOf(
                        "line" to inv.line,
                        "text" to inv.text,
                        "inDataflow" to fileNodeLines.contains(inv.line),
                        "argIdentifiers" to inv.argIdentifiers,
                    )
                }
            } else {
                emptyList()
            }

        val primaryConditions =
            if (primaryIndex != null && primaryMethod != null) {
                primaryIndex.collectConditions(primaryMethod.tree).take(maxConditions).map { cond ->
                    mapOf(
                        "line" to cond.line,
                        "text" to cond.text,
                        "externalSymbols" to cond.externalSymbols,
                    )
                }
            } else {
                emptyList()
            }

        val externalSymbols =
            (if (primaryIndex != null && primaryMethod != null) {
                primaryIndex.collectExternalSymbols(primaryMethod.tree)
            } else {
                emptySet()
            }) +
                (primaryConditions.flatMap { (it["externalSymbols"] as? List<*>)?.filterIsInstance<String>().orEmpty() })

        val externalSymbolSamples =
            externalSymbols
                .asSequence()
                .map { normalizeJavaSymbol(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(maxExternalSymbols)
                .toList()

        val fieldDefinitions =
            if (primaryIndex != null && primaryMethod != null && externalSymbolSamples.isNotEmpty()) {
                primaryIndex.findFieldDefinitionsAround(primaryMethod.tree, externalSymbolSamples).take(30)
            } else {
                emptyList()
            }

        return mapOf(
            "engine" to "java-ast-enricher",
            "generatedAt" to clock.instant().toString(),
            "primary" to mapOf(
                "path" to primaryPathRaw,
                "line" to primaryLine,
                "method" to primarySummary,
                "conditions" to primaryConditions,
                "invocations" to primaryInvocations,
            ),
            "dataflow" to mapOf(
                "nodes" to dataFlowNodes.map { mapOf("id" to it.id, "label" to it.label, "file" to it.file, "line" to it.line) },
                "edges" to dataFlowEdges.map { mapOf("source" to it.source, "target" to it.target, "label" to it.label) },
                "nodeMethods" to dataflowMethods,
            ),
            "externalSymbols" to externalSymbolSamples,
            "fieldDefinitions" to fieldDefinitions,
            "limits" to mapOf(
                "maxFiles" to maxFiles,
                "maxNodes" to maxNodes,
                "maxMethodChars" to maxMethodChars,
                "maxCallSamples" to maxCallSamples,
                "neighborhoodWindowLines" to neighborhoodWindowLines,
            ),
        )
    }

    private data class NodeLocation(
        val nodeId: String,
        val label: String,
        val path: String,
        val line: Int
    )

    private fun extractLine(location: Map<String, Any?>): Int? {
        val raw = location["line"] ?: location["startLine"] ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }
}
