/*
 * Copyright © 2017, Nectar-Server Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those
 * of the authors and should not be interpreted as representing official policies,
 * either expressed or implied, of the FreeBSD Project.
 */
package io.github.jython234.nectar.server.controller;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.jython234.nectar.server.ClientSession;
import io.github.jython234.nectar.server.NectarServerApplication;
import io.github.jython234.nectar.server.Util;
import io.github.jython234.nectar.server.struct.ClientState;
import io.github.jython234.nectar.server.struct.SessionToken;
import io.jsonwebtoken.*;
import io.jsonwebtoken.impl.DefaultClaims;
import lombok.AccessLevel;
import lombok.Getter;
import org.bson.Document;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controller that handles sessions, including
 * token requests and revokes.
 *
 * @author jython234
 */
@RestController
public class SessionController {
    public static final int TOKEN_EXPIRE_TIME = 1800000; // Token expire time is 30 minutes

    @Getter private static SessionController instance;

    private Map<String, ClientSession> sessions;

    public SessionController() {
        this.sessions = new ConcurrentHashMap<>();

        instance = this;
    }

    @Scheduled(fixedDelay = 500) // Check for tokens every half second
    public void checkTokens() {
        sessions.values().forEach((ClientSession session) -> {
            SessionToken token = session.getToken();
            if((System.currentTimeMillis() - token.getTimestamp()) >= token.getExpires()) { // Check if the token has expired
                // Session Token has expired, revoke it
                NectarServerApplication.getLogger().info("Token for " + token.getUuid() + " has expired, session removed.");

                session.updateState(ClientState.UNKNOWN); // Switch to unknown state until it renews it's token
                sessions.remove(token.getUuid());
            } else {
                // Session Token has not expired, check when the last ping was
                if((System.currentTimeMillis() - session.getLastPing()) >= 30000) {
                    // Check if last ping was greater than 30 seconds ago. Ideally the client should ping ever 15 seconds
                    NectarServerApplication.getLogger().info("Last ping for " + token.getUuid() + " was greater than 30 seconds ago, revoking token.");

                    session.updateState(ClientState.UNKNOWN); // They timed out
                    sessions.remove(token.getUuid());
                }
            }
        });
    }

    /**
     * Checks a SessionToken to see if it is found
     * in the issued tokens map.
     * @param token The token to check.
     * @return If the token has been found and verified issued.
     */
    public boolean checkToken(SessionToken token) {
        for(ClientSession session : sessions.values()) {
            if(session.getToken().getServerID().equals(NectarServerApplication.serverID)
                    && session.getToken().getUuid().equals(token.getUuid())
                    && session.getToken().getTimestamp() == token.getTimestamp()
                    && session.getToken().getExpires() == token.getExpires()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get the current ClientState of a client,
     * connected or not.
     * @param uuid The UUID of the client. This must be a valid
     *             UUID which belongs to a client. If the UUID is
     *             not found in the database or connected clients,
     *             then a RuntimeException is thrown.
     * @return The ClientState of the specified client with the UUID.
     */
    public ClientState queryClientState(String uuid) {
        for(ClientSession session : this.sessions.values()) {
            if(session.getToken().getUuid().equals(uuid)) {
                return session.getState();
            }
        }

        // The session is not currently connected, so we need to check the database
        MongoCollection<Document> clients = NectarServerApplication.getDb().getCollection("clients");
        Document doc = clients.find(Filters.eq("uuid", uuid)).first();
        if(doc != null) {
            return ClientState.fromInt(doc.getInteger("state", ClientState.UNKNOWN.toInt()));
        }

        // We couldn't find the client in the database, so throw an exception
        throw new RuntimeException("Failed to find UUID " + uuid + "in connected sessions or in database. Is it invalid?");
    }

    @RequestMapping(NectarServerApplication.ROOT_PATH + "/session/tokenRequest")
    public ResponseEntity<String> tokenRequest(@RequestParam(value = "uuid") String uuid, @RequestParam(value = "auth") String authString,
                                               HttpServletRequest request) {

        MongoCollection<Document> clients = NectarServerApplication.getDb().getCollection("clients");
        Document doc = clients.find(Filters.eq("uuid", uuid)).first();
        if(doc == null) {
            // We can't find this client in the database
            // This means that the client is unregistered, so we drop the request
            NectarServerApplication.getLogger().warn("Received token request from unregistered client "
                    + request.getRemoteAddr() + " with UUID: " + uuid
            );
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("UUID not found in database.");
        } else {
            try {
                String auth = doc.getString("auth");
                if(!auth.equals(Util.computeSHA256(authString))) {
                    // Auth string does not match, unauthenticated
                    NectarServerApplication.getLogger().warn("Attempted token request for" + uuid + " from: "
                            + request.getRemoteAddr() + ", authentication string check failed."
                    );
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Authentication Strings do not match!");
                }
            } catch(Exception e) {
                NectarServerApplication.getLogger().warn("Failed to find auth string for \"" + uuid + "\" in database.");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to find auth string in database.");
            }
        }

        try { // Verify "uuid"
            UUID.fromString(uuid);
        } catch(IllegalArgumentException e) {
            // UUID is invalid
            return ResponseEntity.badRequest().body("Invalid UUID!");
        }

        // Check if we have issued a token already for this UUID
        if(this.sessions.containsKey(uuid)) {
            // Token has been issued
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token already issued for this UUID!");
        }

        SessionToken token = new SessionToken(NectarServerApplication.serverID, uuid, System.currentTimeMillis(), TOKEN_EXPIRE_TIME);
        ClientSession session = new ClientSession(token);
        session.updateState(ClientState.ONLINE); // Client is now online
        this.sessions.put(uuid, session);

        String jwt = Jwts.builder()
                .setPayload(token.constructJSON().toJSONString())
                .signWith(SignatureAlgorithm.ES384, NectarServerApplication.getConfiguration().getServerPrivateKey())
                .compact(); // Sign and build the JWT

        NectarServerApplication.getLogger().info("Issued token for new client " + request.getRemoteAddr() + " with UUID: " + uuid);

        return ResponseEntity.ok(jwt); // Return the token
    }

    @RequestMapping(NectarServerApplication.ROOT_PATH + "/session/updateState")
    public ResponseEntity stateUpdate(@RequestParam(value = "token") String jwtRaw, @RequestParam(value = "state") int state, HttpServletRequest request) {

        ResponseEntity r = Util.verifyJWT(jwtRaw, request);
        if(r != null)
            return r;

        SessionToken token = SessionToken.fromJSON(Util.getJWTPayload(jwtRaw));

        if(this.checkToken(token)) { // Check if the token has expired
            try {
                ClientState cstate = ClientState.fromInt(state);
                this.sessions.get(token.getUuid()).updateState(cstate);

                switch (cstate) {
                    case SHUTDOWN:
                    case SLEEP:
                        // If we are shutting down or sleeping we need to remove the session.
                        this.sessions.remove(token.getUuid());
                        NectarServerApplication.getLogger().info("Revoked token for " + token.getUuid() + ": state changed.");
                        break;
                }

            } catch(IllegalArgumentException e) {
                return ResponseEntity.badRequest().body("Invalid state.");
            }
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token expired/not valid.");
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).body("Success.");
    }

    @RequestMapping(NectarServerApplication.ROOT_PATH + "/session/clientPing")
    public ResponseEntity clientPing(@RequestParam(value = "token") String jwtRaw, @RequestParam(value = "data") String dataRaw,
                                     HttpServletRequest request) {

        ResponseEntity r = Util.verifyJWT(jwtRaw, request);
        if(r != null)
            return r;

        SessionToken token = SessionToken.fromJSON(Util.getJWTPayload(jwtRaw));

        if(this.checkToken(token)) {
            // TODO
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token expired/not valid.");
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).body("Success.");
    }

    @RequestMapping(NectarServerApplication.ROOT_PATH + "/session/queryState")
    public ResponseEntity<Integer> queryState(@RequestParam(value = "uuid") String uuid) {
        if(this.sessions.containsKey(uuid)) {
            return ResponseEntity.ok(this.sessions.get(uuid).getState().toInt());
        }

        // The session requested is not connected. Check the database then.
        MongoCollection<Document> clients = NectarServerApplication.getDb().getCollection("clients");
        Document doc = clients.find(Filters.eq("uuid", uuid)).first();
        if(doc == null) {
            // We couldn't find a client with the UUID, that means it's an invalid client UUID
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(-1);
        } else {
            // We found the client
            // Default value is UNKNOWN, in case the state was never set in the database yet.
            return ResponseEntity.ok(doc.getInteger("state", ClientState.UNKNOWN.toInt()));
        }
    }
}
