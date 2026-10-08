package com.bimba.gateway;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Gson for canonical messages and config.json; the REST API itself is written by Spring (Jackson). */
public final class Json {
    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    public static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

    private Json() {
    }

    /** JSON text -> plain Java lists / maps / numbers (what Spring's JSON writer understands), or null. */
    public static Object toJava(String json) {
        return json == null ? null : GSON.fromJson(json, Object.class);
    }

    /** a string member of a JSON object, "" when missing, null or not a primitive */
    public static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }
}
