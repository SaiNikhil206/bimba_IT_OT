package com.bimba.setu.collect;

import com.bimba.setu.collect.Canonical.Quality;
import com.bimba.setu.config.TagModel;

import java.time.Instant;
import java.util.List;

/** A connection to one field device. Read cycles run on one thread per device, so drivers need no locking. */
public interface Driver extends AutoCloseable {
    /** One value per tag, in the same order. Throws when the device cannot be reached (all tags go BAD). */
    List<Reading> read(List<TagModel.Tag> tags) throws Exception;

    /** e.g. "plc-sim:5020 unit 1" */
    String describe();

    @Override
    void close();

    /**
     * value is Boolean, Double or String; detail says why the reading is not GOOD; sourceTime is the device's own
     * time for the value (null when the protocol has none, e.g. Modbus).
     */
    record Reading(Object value, Quality quality, String detail, Instant sourceTime) {
        public static Reading ok(Object v) {
            return new Reading(v, Quality.GOOD, null, null);
        }

        public static Reading ok(Object v, Instant sourceTime) {
            return new Reading(v, Quality.GOOD, null, sourceTime);
        }

        public static Reading uncertain(Object v, String detail, Instant sourceTime) {
            return new Reading(v, Quality.UNCERTAIN, detail, sourceTime);
        }

        public static Reading bad(String detail) {
            return new Reading(null, Quality.BAD, detail, null);
        }

        public boolean good() {
            return quality == Quality.GOOD;
        }
    }
}
