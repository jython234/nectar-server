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
            if(session.getToken().getUuid().equals(token.getUuid())
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
    public ResponseEntity<String> tokenRequest(@RequestParam(value="uuid") String uuid, HttpServletRequest request) {

        MongoCollection<Document> clients = NectarServerApplication.getDb().getCollection("clients");
        Document doc = clients.find(Filters.eq("uuid", uuid)).first();
        if(doc == null) {
            // We can't find this client in the database
            // This means that the client is unregistered, so we drop the request
            NectarServerApplication.getLogger().warn("Received token request from unregistered client "
                    + request.getRemoteAddr() + " with UUID: " + uuid
            );
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("UUID not found in database.");
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

        SessionToken token = new SessionToken(uuid, System.currentTimeMillis(), TOKEN_EXPIRE_TIME);
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

        try {
            Jwts.parser().setSigningKey(NectarServerApplication.getConfiguration().getServerPublicKey())
                .parse(jwtRaw); // Verify signature
        } catch(MalformedJwtException e) {
            NectarServerApplication.getLogger().warn("Malformed JWT from client \"" + request.getRemoteAddr()
                    + "\" while processing token request."
            );
            return ResponseEntity.badRequest().body("JWT \"clientInfo\" is malformed!");
        } catch(SignatureException e) {
            NectarServerApplication.getLogger().warn("Invalid JWT signature from client \"" + request.getRemoteAddr()
                    + "\" while processing token request."
            );
            return ResponseEntity.badRequest().body("JWT \"clientInfo\" signature is invalid!");
        } catch(Exception e) {
            NectarServerApplication.getLogger().error(" Failed to verify JWT from client \"" + request.getRemoteAddr()
                    + "\" while processing token request."
            );
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to verify JWT.");
        }

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