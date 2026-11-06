package com.petsa.http;

import java.util.Map;

/** The authenticated caller. role is ADMIN, FINANCE or EMPLOYEE; employee is null unless role is EMPLOYEE. */
public record Session(long userId, String loginId, String role, String displayName,
                      String employeeId, Map<String, Object> employee) {
    public boolean isAdmin() { return "ADMIN".equals(role); }
    public String roleName() { return role.toLowerCase(java.util.Locale.ROOT); }
}
