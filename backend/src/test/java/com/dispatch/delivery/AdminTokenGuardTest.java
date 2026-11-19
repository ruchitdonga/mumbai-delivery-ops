package com.dispatch.delivery;

import com.dispatch.delivery.api.AdminTokenGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class AdminTokenGuardTest {

    @Test
    void postsNeedTheTokenReadsDoNot() throws Exception {
        AdminTokenGuard guard = new AdminTokenGuard("s3cret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(guard.preHandle(new MockHttpServletRequest("GET", "/api/ops/snapshot"), res, null));
        assertFalse(guard.preHandle(new MockHttpServletRequest("POST", "/api/ops/orders/1/cancel"), res, null));
        assertEquals(401, res.getStatus());
        MockHttpServletRequest wrong = new MockHttpServletRequest("POST", "/api/ops/sim/control");
        wrong.addHeader("X-Admin-Token", "guess");
        assertFalse(guard.preHandle(wrong, new MockHttpServletResponse(), null));
        MockHttpServletRequest right = new MockHttpServletRequest("POST", "/api/ops/sim/control");
        right.addHeader("X-Admin-Token", "s3cret");
        assertTrue(guard.preHandle(right, new MockHttpServletResponse(), null));
    }

    @Test
    void noTokenConfiguredMeansOpen() throws Exception { // default local setup
        assertTrue(new AdminTokenGuard("").preHandle(new MockHttpServletRequest("POST", "/api/ops/sim/control"), new MockHttpServletResponse(), null));
    }
}
