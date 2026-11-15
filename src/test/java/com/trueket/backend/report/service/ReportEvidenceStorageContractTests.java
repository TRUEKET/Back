package com.trueket.backend.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportEvidenceStorageContractTests {
    @Test void copyAndCleanupUseOfficialStorageWireContract() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new ArrayList<String>();
        var json=new ObjectMapper();
        String from="pending/15/550e8400-e29b-41d4-a716-446655440000.png";
        String to="reports/15/550e8400-e29b-41d4-a716-446655440001.png";
        server.createContext("/storage/v1/object/",e->{
            var body=json.readTree(e.getRequestBody());
            boolean correct;
            if(e.getRequestMethod().equals("POST")) {
                correct=e.getRequestURI().getPath().equals("/storage/v1/object/copy")
                        && body.path("bucketId").asText().equals("report-evidence")
                        && !body.has("sourceBucket")
                        && body.path("sourceKey").asText().equals(from)
                        && body.path("destinationKey").asText().equals(to);
            } else {
                correct=e.getRequestMethod().equals("DELETE")
                        && e.getRequestURI().getPath().equals("/storage/v1/object/report-evidence")
                        && body.path("prefixes").isArray() && body.path("prefixes").size()==1
                        && Set.of(from,to).contains(body.path("prefixes").get(0).asText());
            }
            calls.add(e.getRequestMethod()+":"+correct);
            byte[] data="[]".getBytes();e.sendResponseHeaders(correct?200:400,data.length);
            e.getResponseBody().write(data);e.close();
        });server.start();
        try {
            var store=new SupabaseReportEvidenceStore("http://127.0.0.1:"+server.getAddress().getPort(),
                    "synthetic-key","report-evidence");
            store.copyPendingToReport(from,to);
            store.deletePending(from);store.deleteReport(to);store.deleteReport(to);
            assertEquals(List.of("POST:true","DELETE:true","DELETE:true","DELETE:true"),calls);
        } finally {server.stop(0);}
    }
}
