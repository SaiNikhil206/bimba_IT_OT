package com.bimba.setu.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Who sells and supports this Setu, shown on the public promotion page (ui/src/pages/Promo.jsx) so a visitor can reach
 * them: company name, sales e-mail, phone and website (bimba.contact.*, env BIMBA_CONTACT_*). Nothing is made up: a value
 * that is not set, or not valid, is left out and the page hides what it would have shown.
 */
@Component
public class ContactInfo {
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9 ()./-]{6,30}");
    private static final Pattern WEBSITE = Pattern.compile("https?://[^\\s\"<>]+");

    private final Map<String, String> json = new LinkedHashMap<>();

    public ContactInfo(@Value("${bimba.contact.company:}") String company, @Value("${bimba.contact.email:}") String email,
                       @Value("${bimba.contact.phone:}") String phone, @Value("${bimba.contact.website:}") String website) {
        put("company", company, null);
        put("email", email, EMAIL);
        put("phone", phone, PHONE);
        put("website", website, WEBSITE);
    }

    private void put(String key, String value, Pattern valid) {
        String v = value == null ? "" : value.trim();
        if (!v.isEmpty() && v.length() <= 200 && (valid == null || valid.matcher(v).matches())) {
            json.put(key, v);
        }
    }

    /** the values that are set, e.g. {company, email}; empty when none are */
    public Map<String, String> json() {
        return json;
    }
}
