package com.platform.almbackend.permission;

import java.util.Set;

public record AccessScope(boolean all, Set<Long> projectIds) {
    public static AccessScope global() { return new AccessScope(true, Set.of()); }
    public static AccessScope of(Set<Long> ids) { return new AccessScope(false, Set.copyOf(ids)); }
}

