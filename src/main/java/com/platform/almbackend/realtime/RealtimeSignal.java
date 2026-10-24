package com.platform.almbackend.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로젝트 실시간 스트림의 <b>무효화 신호</b> 한 건(2026-10-24). 본문(제목·설명·필드 값)은 싣지 않는다 — 받는 쪽이 id로
 * REST를 다시 읽고, 그 REST가 권한을 거른다(Redis Streams 이벤트와 같은 원칙).
 *
 * @param id       스트림 위치(Redis Stream 엔트리 id 형식 {@code <ms>-<seq>}). SSE {@code id:}가 되고 재연결 때
 *                 {@code Last-Event-ID}로 돌아온다. 팬아웃 저장소에 못 적은 신호(이 노드에만 전달)는 null
 * @param event    SSE 이벤트 이름 — {@code issue} · {@code sprint}
 * @param kind     issue: created · updated · transitioned · deleted · archived · restored · ranked /
 *                 sprint: created · updated · started · completed · deleted
 * @param version  변경 뒤 version(이슈·스프린트). 받는 쪽은 들고 있는 version 이상이면 재조회를 건너뛸 수 있다. 삭제는 null
 * @param actorId  변경한 사용자(JWT sub). 시스템(사이클 자동 생성)은 0 — 받는 쪽이 자기 변경 메아리를 거를 때 쓴다
 */
public record RealtimeSignal(
        String id,
        String event,
        String kind,
        long projectId,
        Long issueId,
        String issueKey,
        Long sprintId,
        Long version,
        List<String> changedFields,
        long actorId,
        Instant at) {

    public RealtimeSignal {
        changedFields = changedFields == null ? null : List.copyOf(changedFields);
    }

    public RealtimeSignal withId(String newId) {
        return new RealtimeSignal(newId, event, kind, projectId, issueId, issueKey, sprintId, version, changedFields, actorId, at);
    }

    /** 클라이언트 페이로드({@code data:}) — {@code type}은 {@code event.kind}. null 필드는 뺀다 */
    public Map<String, Object> payload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", event + "." + kind);
        out.put("projectId", projectId);
        if (issueId != null) out.put("issueId", issueId);
        if (issueKey != null) out.put("issueKey", issueKey);
        if (sprintId != null) out.put("sprintId", sprintId);
        if (version != null) out.put("version", version);
        if (changedFields != null) out.put("changedFields", changedFields);
        out.put("actorId", actorId);
        out.put("at", at.toString());
        return out;
    }

    public String toJson(ObjectMapper json) {
        try {
            return json.writeValueAsString(payload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #toJson} 역변환(팬아웃 저장소에서 읽을 때). 모양이 어긋나면 IllegalArgumentException */
    public static RealtimeSignal fromJson(ObjectMapper json, String id, String text) {
        try {
            JsonNode node = json.readTree(text);
            String type = node.path("type").asText("");
            int dot = type.indexOf('.');
            if (dot <= 0 || !node.hasNonNull("projectId") || !node.hasNonNull("at")) {
                throw new IllegalArgumentException("실시간 신호 모양이 아닙니다");
            }
            List<String> fields = null;
            if (node.has("changedFields")) {
                fields = new ArrayList<>();
                for (JsonNode f : node.get("changedFields")) fields.add(f.asText());
            }
            return new RealtimeSignal(id, type.substring(0, dot), type.substring(dot + 1), node.get("projectId").asLong(),
                    node.hasNonNull("issueId") ? node.get("issueId").asLong() : null,
                    node.hasNonNull("issueKey") ? node.get("issueKey").asText() : null,
                    node.hasNonNull("sprintId") ? node.get("sprintId").asLong() : null,
                    node.hasNonNull("version") ? node.get("version").asLong() : null,
                    fields, node.path("actorId").asLong(0), Instant.parse(node.get("at").asText()));
        } catch (JsonProcessingException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("실시간 신호를 읽지 못했습니다", e);
        }
    }
}
