package com.bimba.gateway.collect;

import com.bimba.gateway.config.TagModel;

import java.util.List;

/** A connection to one field device. Read cycles run on one thread per device, so drivers need no locking. */
interface Driver extends AutoCloseable {
    /** One value per tag, in the same order. Throws when the device cannot be reached (all tags go BAD). */
    List<Reading> read(List<TagModel.Tag> tags) throws Exception;

    /** e.g. "plc-sim:5020 unit 1" */
    String describe();

    @Override
    void close();

    /** value is Boolean, Double or String; good=false carries the reason in detail */
    record Reading(Object value, boolean good, String detail) {
        static Reading ok(Object v) {
            return new Reading(v, true, null);
        }

        static Reading bad(String detail) {
            return new Reading(null, false, detail);
        }
    }
}
