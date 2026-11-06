package com.petsa.http;

public enum Access {
    PUBLIC, ANY,
    ADMIN,      // System Admin only
    FINANCE,    // Finance Admin only
    STAFF,      // System Admin or Finance Admin
    EMPLOYEE;   // Employees only

    public boolean allows(String role) {
        return switch (this) {
            case PUBLIC, ANY -> true;
            case ADMIN -> "ADMIN".equals(role);
            case FINANCE -> "FINANCE".equals(role);
            case STAFF -> "ADMIN".equals(role) || "FINANCE".equals(role);
            case EMPLOYEE -> "EMPLOYEE".equals(role);
        };
    }
}
