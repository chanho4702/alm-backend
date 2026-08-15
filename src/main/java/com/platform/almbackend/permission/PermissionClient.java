package com.platform.almbackend.permission;

public interface PermissionClient {
    boolean isAllowed(long userId, long projectId, AlmAction action);
    AccessScope accessibleProjects(long userId);
    boolean grantProjectAdmin(long userId, long projectId);
    int revokeProjectGrants(long projectId);
}

