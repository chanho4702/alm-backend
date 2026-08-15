package com.platform.almbackend.event;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.Project;
import com.platform.proto.events.v1.*;

import java.util.UUID;

public final class AlmEvents {
    private AlmEvents() {}

    private static EventEnvelope.Builder base(long actorId) {
        return EventEnvelope.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(System.currentTimeMillis())
                .setActorId(actorId)
                .setSource("alm-backend");
    }

    public static EventEnvelope projectCreated(long actorId, Project project) {
        return base(actorId).setProjectCreated(ProjectCreated.newBuilder()
                .setProjectId(project.getId()).setKey(project.getKey()).setName(project.getName())).build();
    }

    public static EventEnvelope projectUpdated(long actorId, Project project) {
        return base(actorId).setProjectUpdated(ProjectUpdated.newBuilder()
                .setProjectId(project.getId()).setKey(project.getKey()).setName(project.getName())).build();
    }

    public static EventEnvelope projectDeleted(long actorId, long projectId) {
        return base(actorId).setProjectDeleted(ProjectDeleted.newBuilder().setProjectId(projectId)).build();
    }

    public static EventEnvelope issueCreated(long actorId, Issue issue) {
        return base(actorId).setIssueCreated(IssueCreated.newBuilder()
                .setIssueId(issue.getId()).setProjectId(issue.getProjectId())
                .setIssueKey(issue.getKey()).setTitle(issue.getTitle())).build();
    }

    public static EventEnvelope issueUpdated(long actorId, Issue issue) {
        return base(actorId).setIssueUpdated(IssueUpdated.newBuilder()
                .setIssueId(issue.getId()).setProjectId(issue.getProjectId())
                .setIssueKey(issue.getKey()).setTitle(issue.getTitle()).setVersion(issue.getVersion())).build();
    }

    public static EventEnvelope issueDeleted(long actorId, Issue issue) {
        return base(actorId).setIssueDeleted(IssueDeleted.newBuilder()
                .setIssueId(issue.getId()).setProjectId(issue.getProjectId()).setIssueKey(issue.getKey())).build();
    }
}

