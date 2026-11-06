package com.petsa.http;

@FunctionalInterface
public interface Handler {
    /** Return a Map/List (sent as JSON 200), a Res for another status, or null for 204. */
    Object handle(Ctx c) throws Exception;
}
