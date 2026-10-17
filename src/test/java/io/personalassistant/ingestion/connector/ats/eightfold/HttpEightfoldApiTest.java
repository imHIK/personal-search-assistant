package io.personalassistant.ingestion.connector.ats.eightfold;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class HttpEightfoldApiTest {

    @Test
    void cookieHeaderKeepsNameValuePairsAndDropsAttributes() {
        String header = HttpEightfoldApi.cookieHeader(List.of(
                "_vscidv2=p1; Domain=microsoft.com; Secure; HttpOnly; Path=/",
                "_vs=792:1791918892.62:416; Domain=microsoft.com; Expires=Wed, 13 Oct 2027 19:14:52 GMT",
                "_vscid=2"));

        Assertions.assertEquals("_vscidv2=p1; _vs=792:1791918892.62:416; _vscid=2", header);
    }

    @Test
    void cookieHeaderIsEmptyWhenNothingUsableIsSet() {
        Assertions.assertEquals("", HttpEightfoldApi.cookieHeader(List.of()));
        Assertions.assertEquals("", HttpEightfoldApi.cookieHeader(List.of("; Path=/", "=orphan")));
    }
}
