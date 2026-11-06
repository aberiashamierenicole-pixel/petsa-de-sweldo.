package com.petsa.http;

public record Res(int status, Object body) {
    public static Res created(Object body) { return new Res(201, body); }
}
