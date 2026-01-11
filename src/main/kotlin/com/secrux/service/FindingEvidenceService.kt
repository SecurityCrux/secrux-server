package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.dto.CodeLineDto
import com.secrux.dto.CodeSnippetDto
import com.secrux.dto.DataFlowEdgeDto
import com.secrux.dto.DataFlowNodeDto
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

@Component
class FindingEvidenceService(
    @Value("\${secrux.workspace.root:build/workspaces}") private val workspaceRootDir: String
) {

    fun normalizeWorkspaceNodeFile(taskId: UUID, raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalized = value.replace('\\', '/')
        val rootAbsolute = Path.of(workspaceRootDir).toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
        val rootRelative = workspaceRootDir.replace('\\', '/').trimEnd('/')
        val id = taskId.toString()
        val absolutePrefix = "$rootAbsolute/$id/"
        val relativePrefix = "$rootRelative/$id/"
        return when {
            normalized.startsWith(absolutePrefix) -> normalized.removePrefix(absolutePrefix)
            normalized.startsWith(relativePrefix) -> normalized.removePrefix(relativePrefix)
            else -> value
        }
    }

    fun loadSnippet(location: Map<String, Any?>, context: Int = 5): CodeSnippetDto? {
        val pathValue = location["path"] as? String ?: return null
        val lineValue = (location["line"] as? Number)?.toInt() ?: (location["startLine"] as? Number)?.toInt()
        val line = lineValue ?: return null
        val filePath = Path.of(pathValue)
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return null
        }
        val lines = Files.readAllLines(filePath)
        val start = max(1, line - context)
        val end = min(lines.size, line + context)
        val snippetLines = mutableListOf<CodeLineDto>()
        for (idx in (start..end)) {
            val content = lines[idx - 1].take(400)
            snippetLines.add(CodeLineDto(lineNumber = idx, content = content, highlight = idx == line))
        }
        return CodeSnippetDto(
            path = filePath.toString(),
            startLine = start,
            endLine = end,
            lines = snippetLines
        )
    }

    fun getSnippetForTaskWorkspace(taskId: UUID, path: String, line: Int, context: Int): CodeSnippetDto? {
        val safeContext = context.coerceIn(0, 50)
        if (line <= 0) return null
        val candidate = resolveTaskWorkspaceFilePath(taskId, path)
        val location = mapOf("path" to candidate.toString(), "line" to line)
        return loadSnippet(location, safeContext)
    }

    fun resolveTaskWorkspaceFilePath(taskId: UUID, path: String): Path {
        val workspaceRoot = Path.of(workspaceRootDir).toAbsolutePath().normalize()
        val taskWorkspace = workspaceRoot.resolve(taskId.toString()).normalize()
        val requested = Path.of(path)
        val candidates =
            buildList<Path> {
                if (requested.isAbsolute) {
                    add(requested)
                } else {
                    add(taskWorkspace.resolve(requested))
                    add(requested.toAbsolutePath())
                }
            }.map { it.toAbsolutePath().normalize() }
        return candidates.firstOrNull { it.startsWith(taskWorkspace) }
            ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "Requested snippet path is outside task workspace")
    }

    fun parseDataflow(evidence: Map<String, Any?>?): Pair<List<DataFlowNodeDto>, List<DataFlowEdgeDto>> {
        val df = evidence?.get("dataflow") ?: evidence?.get("dataFlow") ?: return emptyList<DataFlowNodeDto>() to emptyList()
        val map = df as? Map<*, *> ?: return emptyList<DataFlowNodeDto>() to emptyList()
        val nodesRaw = map["nodes"] as? List<*> ?: emptyList<Any?>()
        val edgesRaw = map["edges"] as? List<*> ?: emptyList<Any?>()
        val nodes =
            nodesRaw.mapNotNull { raw ->
                val obj = raw as? Map<*, *> ?: return@mapNotNull null
                val id = obj["id"]?.toString() ?: return@mapNotNull null
                val label = obj["label"]?.toString() ?: id
                val inferred = inferSemgrepRoleAndValue(label)
                val role = obj["role"]?.toString() ?: inferred.first
                val value = obj["value"]?.toString() ?: inferred.second
                DataFlowNodeDto(
                    id = id,
                    label = value ?: label,
                    role = role,
                    value = value,
                    file = obj["file"]?.toString(),
                    line = (obj["line"] as? Number)?.toInt(),
                    startColumn = (obj["startColumn"] as? Number)?.toInt(),
                    endColumn = (obj["endColumn"] as? Number)?.toInt()
                )
            }
        val edges =
            edgesRaw.mapNotNull { raw ->
                val obj = raw as? Map<*, *> ?: return@mapNotNull null
                val source = obj["source"]?.toString() ?: return@mapNotNull null
                val target = obj["target"]?.toString() ?: return@mapNotNull null
                DataFlowEdgeDto(
                    source = source,
                    target = target,
                    label = obj["label"]?.toString()
                )
            }
        return nodes to edges
    }

    fun hasDataFlow(evidence: Map<String, Any?>?): Boolean {
        val (nodes, edges) = parseDataflow(evidence)
        return nodes.isNotEmpty() || edges.isNotEmpty()
    }

    private fun inferSemgrepRoleAndValue(label: String): Pair<String?, String?> {
        val trimmed = label.trim()
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return null to null
        val prefix = trimmed.substring(0, colon).trim()
        val role =
            when (prefix.lowercase()) {
                "source" -> "SOURCE"
                "propagator" -> "PROPAGATOR"
                "sink" -> "SINK"
                else -> null
            } ?: return null to null
        var rest = trimmed.substring(colon + 1).trim()
        val atIndex = rest.indexOf(" @")
        if (atIndex >= 0) {
            rest = rest.substring(0, atIndex).trim()
        }
        val value = rest.trim().trim('\'', '"').takeIf { it.isNotBlank() }
        return role to value
    }
}
