package com.minion.core.tools.browser;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 不把相同数据页/重复唯一键计为新增覆盖。 */
final class PaginationEvidence {
    private final Set<String> fingerprints = new HashSet<String>(), markers = new HashSet<String>(), keys = new HashSet<String>();
    private final JsonArray pages = new JsonArray(), records = new JsonArray(), duplicates = new JsonArray();
    private final Set<Integer> pageNumbers = new TreeSet<Integer>();
    private String failure = "";
    static String fingerprint(JsonObject page) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(page.getAsJsonArray("rows").toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format("%02x",b & 255)); return out.toString();
    }
    boolean add(JsonObject page) throws Exception {
        String fingerprint = fingerprint(page), marker = BrowserReliability.text(page,"marker");
        if (!fingerprints.add(fingerprint) || (!marker.isEmpty() && !markers.add(marker))) { failure="duplicate_page"; return false; }
        JsonArray rows = page.getAsJsonArray("rows");
        if (rows==null || rows.size()==0) { failure="empty_page"; return false; }
        JsonObject meta = page.deepCopy(); meta.remove("rows"); meta.addProperty("dataSha256",fingerprint); meta.addProperty("rowCount",rows.size()); pages.add(meta);
        if (marker.matches("[0-9]+")) try { pageNumbers.add(Integer.parseInt(marker)); } catch (NumberFormatException ignored) { }
        for (JsonElement item : rows) {
            JsonObject row = item.getAsJsonObject(); String key = BrowserReliability.text(row,"key");
            if (key.trim().isEmpty()) { failure="missing_unique_key"; continue; }
            if (!keys.add(key)) { duplicates.add(key); failure="duplicate_record"; continue; }
            records.add(row.deepCopy());
        }
        return failure.isEmpty();
    }
    JsonObject report(boolean exhausted, int expectedPages, int expectedRecords, int firstPage) {
        boolean countMatches=(expectedPages<0||pages.size()==expectedPages)&&(expectedRecords<0||records.size()==expectedRecords);
        boolean numericCoverage=true;
        if (firstPage>=0) {
            numericCoverage=pageNumbers.size()==pages.size();
            for(int i=0;i<pages.size();i++) numericCoverage &= pageNumbers.contains(firstPage+i);
        }
        String status=!failure.isEmpty()||!countMatches||!numericCoverage?"failed"
                :exhausted&&(expectedPages>=0||expectedRecords>=0||firstPage>=0)?"passed":"partial";
        JsonObject out=new JsonObject(); out.addProperty("status",status); out.addProperty("reason",failure);
        out.addProperty("exhausted",exhausted); out.addProperty("expectedPages",expectedPages); out.addProperty("expectedRecords",expectedRecords);
        out.addProperty("observedPages",pages.size()); out.addProperty("uniqueRecords",records.size()); out.addProperty("numericCoverage",numericCoverage);
        out.addProperty("coverage", "passed".equals(status)?"asserted_range_observed":"not_proven_complete");
        out.add("pages",pages); out.add("records",records); out.add("duplicateKeys",duplicates); return out;
    }
}
