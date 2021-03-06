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

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.jython234.nectar.server.EventLog;
import io.github.jython234.nectar.server.NectarServerApplication;
import io.github.jython234.nectar.server.Util;
import io.github.jython234.nectar.server.struct.ManagementSessionToken;
import io.github.jython234.nectar.server.struct.SessionToken;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureException;
import org.apache.commons.io.FileUtils;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.print.Doc;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller that handles user
 * authentication, mapped under the
 * "/auth" path.
 *
 * @author jython234
 */
@RestController
public class AuthController {

    @SuppressWarnings("unchecked")
    @RequestMapping(value = NectarServerApplication.ROOT_PATH + "/auth/login", method = RequestMethod.POST)
    public ResponseEntity<String> login(@RequestParam(value = "token") String jwtRaw, @RequestParam(value = "user") String username,
                                @RequestParam(value = "password") String password, HttpServletRequest request) {

        ResponseEntity r = Util.verifyJWT(jwtRaw, request);
        if(r != null)
            return r;

        SessionToken token = SessionToken.fromJSON(Util.getJWTPayload(jwtRaw));
        if(token == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid TOKENTYPE.");

        if(SessionController.getInstance().checkToken(token)) {
            MongoCollection<Document> clients = NectarServerApplication.getDb().getCollection("clients");
            MongoCollection<Document> users = NectarServerApplication.getDb().getCollection("users");
            Document clientDoc = clients.find(Filters.eq("uuid", token.getUuid())).first();

            if(clientDoc == null) {
                NectarServerApplication.getLogger().warn("Failed to find Client Entry in database for " + token.getUuid());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to find entry in database for client.");
            }

            String loggedInUser;
            try {
                // getString will throw an exception if the key is not present in the document
                loggedInUser = clientDoc.getString("loggedInUser");
                if(loggedInUser.equals("none")) {
                    // No user is logged in
                    throw new RuntimeException(); // Move to catch block
                }

                NectarServerApplication.getLogger().warn("Attempted duplicate user login to already logged in client: " + token.getUuid());
                return ResponseEntity.status(HttpStatus.CONFLICT).body("A User is already logged in under this client!");
            } catch(Exception e) {
                // No user is logged in
                Document userDoc = users.find(Filters.eq("username", username)).first();
                if(userDoc == null) {
                    // The user trying to log in does not exist
                    NectarServerApplication.getLogger().warn("Attempted user login for \"" + username + "\", from "
                            + token.getUuid() + ", user not found in database."
                    );

                    NectarServerApplication.getEventLog().addEntry(EventLog.EntryLevel.WARNING, "Attempted user login from non-existent user " + username);

                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found in database!");
                }

                // Check their password
                if(userDoc.getString("password").equals(Util.computeSHA512(password))) {
                    // Password check complete, now update the database with the state
                    clients.updateOne(Filters.eq("uuid", token.getUuid()),
                            new Document("$set", new Document("loggedInUser", username))
                    );
                    NectarServerApplication.getEventLog().logEntry(EventLog.EntryLevel.INFO, "User \"" + username + "\" logged in from " + token.getUuid() + ", traced from " + request.getRemoteAddr());
                } else {
                    NectarServerApplication.getLogger().warn("ATTEMPTED LOGIN TO USER \"" + username + "\": incorrect password from " + token.getUuid() +", address: " + request.getRemoteAddr());
                    NectarServerApplication.getEventLog().addEntry(EventLog.EntryLevel.WARNING, "Failed login to user " + username + " from " + token.getUuid() + ", traced from " + request.getRemoteAddr());
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Password Incorrect!");
                }
            }
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token expired/not valid.");
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).body("Success.");
    }

    @SuppressWarnings("unchecked")
    @RequestMapping(NectarServerApplication.ROOT_PATH + "/auth/logout")
    public ResponseEntity<String> logout(@RequestParam(value = "token") String jwtRaw, HttpServletRequest request) {
        ResponseEntity r = Util.verifyJWT(jwtRaw, request);
        if(r != null)
            return r;

        SessionToken token = SessionToken.fromJSON(Util.getJWTPayload(jwtRaw));
        if(token == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid TOKENTYPE.");

        if(SessionController.getInstance().checkToken(token)) {
            MongoCollection<Document> clients = NectarServerApplication.getDb().getCollection("clients");
            Document clientDoc = clients.find(Filters.eq("uuid", token.getUuid())).first();

            if(clientDoc == null) {
                NectarServerApplication.getLogger().warn("Failed to find Client Entry in database for " + token.getUuid());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to find entry in database for client.");
            }

            String loggedInUser;

            try {
                // getString will throw an exception if the key is not present in the document
                loggedInUser = clientDoc.getString("loggedInUser");
                if(loggedInUser.equals("none")) {
                    // No user is logged in
                    throw new RuntimeException(); // Move to catch block
                }
            } catch(Exception e) {
                return ResponseEntity.badRequest().body("No user is currently logged in!");
            }

            clients.updateOne(Filters.eq("uuid", token.getUuid()),
                    new Document("$set", new Document("loggedInUser", "none"))
            );
            NectarServerApplication.getEventLog().logEntry(EventLog.EntryLevel.INFO, "User \"" + loggedInUser + "\" logged out from " + token.getUuid() + ", traced from " + request.getRemoteAddr());
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token expired/not valid.");
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).body("Success.");
    }

    @SuppressWarnings("unchecked")
    @RequestMapping(value = NectarServerApplication.ROOT_PATH + "/auth/registerClient", method = RequestMethod.POST)
    public ResponseEntity<String> registerClient(@RequestParam(value = "token") String jwtRaw,
                                                 @RequestParam(value = "clientInfo") String clientInfo,
                                                 HttpServletRequest request) {

        ResponseEntity r = Util.verifyJWT(jwtRaw, request);
        if(r != null)
            return r;

        ManagementSessionToken token = ManagementSessionToken.fromJSON(Util.getJWTPayload(jwtRaw));
        if(token == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid TOKENTYPE.");

        if(SessionController.getInstance().checkManagementToken(token)) {
            return ResponseEntity.ok(registerClientToDatabase(request.getRemoteAddr()).toJSONString());
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token expired/invalid.");
        }
    }

    @SuppressWarnings("unchecked")
    @RequestMapping(value = NectarServerApplication.ROOT_PATH + "/auth/registerUser", method = RequestMethod.POST)
    public ResponseEntity<String> registerUser(@RequestParam(value = "token") String jwtRaw, @RequestParam(value = "user") String username,
                                               @RequestParam(value = "password") String password, @RequestParam(value = "admin") boolean admin,
                                               HttpServletRequest request) {
        ResponseEntity r = Util.verifyJWT(jwtRaw, request);
        if(r != null)
            return r;

        ManagementSessionToken token = ManagementSessionToken.fromJSON(Util.getJWTPayload(jwtRaw));
        if(token == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid TOKENTYPE.");

        if(SessionController.getInstance().checkManagementToken(token)) {
            MongoCollection<Document> users = NectarServerApplication.getDb().getCollection("users");

            if(users.find(Filters.eq("username", username)).first() != null)
                return ResponseEntity.status(HttpStatus.CONFLICT).body("Username already exists!");

            // TODO: RUN MORE USERNAME AND PASSWORD REGEX CHECKS!
            if(username.equals("null")) {
                NectarServerApplication.getEventLog().logEntry(EventLog.EntryLevel.NOTICE, "Failed user registration from " + request.getRemoteAddr() + ": invalid username \"null\"");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("\"null\" is an invalid username.");
            }

            users.insertOne(new Document()
                    .append("username", username)
                    .append("password", Util.computeSHA512(password))
                    .append("admin", admin)
                    .append("registeredAt", System.currentTimeMillis())
                    .append("registeredBy", request.getRemoteAddr()));

            // Create new FTS store

            File storeLocation = new File(NectarServerApplication.getConfiguration().getFtsDirectory() + File.separator
                    + "usrStore" + File.separator + username
            );
            if(!storeLocation.mkdir()) {
                NectarServerApplication.getLogger().warn("Failed to create FTS store for new user \"" + username + "\" (mkdir failed)!");
            }

            NectarServerApplication.getEventLog().logEntry(EventLog.EntryLevel.INFO, "Registered new user \"" + username + "\", admin: " + admin + ", by MANAGEMENT SESSION: " + token.getClientIP());
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token expired/not valid.");
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).body("Success.");
    }

    @RequestMapping(NectarServerApplication.ROOT_PATH + "/auth/removeUser")
    public ResponseEntity removeUser(@RequestParam(value = "token") String jwtRaw, @RequestParam(value = "user") String username, HttpServletRequest request) {
        ResponseEntity r = Util.verifyJWT(jwtRaw, request);
        if(r != null)
            return r;

        ManagementSessionToken token = ManagementSessionToken.fromJSON(Util.getJWTPayload(jwtRaw));
        if(token == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid TOKENTYPE.");

        if(SessionController.getInstance().checkManagementToken(token)) {
            MongoCollection<Document> users = NectarServerApplication.getDb().getCollection("users");
            MongoCollection<Document> clients = NectarServerApplication.getDb().getCollection("clients");

            // Check that the user exists
            Document clientDoc = users.find(Filters.eq("username", username)).first();
            if(clientDoc == null)
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Username not found in database!");

            // Check that the user is not signed in
            FindIterable<Document> clientsWithUserSignedIn = clients.find(Filters.eq("loggedInUser", username));
            if(clientsWithUserSignedIn.first() != null)
                return ResponseEntity.status(HttpStatus.CONFLICT).body("The user is currently signed into a client!");

            // Delete the user entry in the database
            users.deleteOne(Filters.eq("username", username));

            // Remove the user's FTS store

            File storeLocation = new File(NectarServerApplication.getConfiguration().getFtsDirectory() + File.separator
                    + "usrStore" + File.separator + username
            );

            try {
                FileUtils.deleteDirectory(storeLocation);
            } catch (IOException e) {
                NectarServerApplication.getLogger().warn("Failed to delete FTS store for former user \"" + username + "\"");
                NectarServerApplication.getEventLog().addEntry(EventLog.EntryLevel.WARNING, "Failed to delete FTS store while deleting user " + username);
            }

            NectarServerApplication.getEventLog().logEntry(EventLog.EntryLevel.INFO, "Removed user \"" + username + "\" by MANAGEMENT SESSION: " + token.getClientIP());
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token expired/not valid.");
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).body("Success.");
    }

    @RequestMapping(NectarServerApplication.ROOT_PATH + "/auth/removeClient")
    public ResponseEntity removeClient(@RequestParam(value = "token") String jwtRaw, @RequestParam(value = "uuid") String uuid,
                                       HttpServletRequest request) {
        ResponseEntity r = Util.verifyJWT(jwtRaw, request);
        if(r != null)
            return r;

        ManagementSessionToken token = ManagementSessionToken.fromJSON(Util.getJWTPayload(jwtRaw));
        if(token == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid TOKENTYPE.");

        if(SessionController.getInstance().checkManagementToken(token)) {
            MongoCollection<Document> clients = NectarServerApplication.getDb().getCollection("clients");

            Document client = clients.find(Filters.eq("uuid", uuid)).first();

            if(client == null) // Check if the client exists
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Client not found in database.");

            if(!(client.getOrDefault("loggedInUser", "null").equals("null"))) { // Check if a user is currently signed into the client.
                NectarServerApplication.getLogger().warn("Attempted client deletion from " + request.getRemoteAddr() + ", a user is already signed into client " + uuid);
                return ResponseEntity.status(HttpStatus.CONFLICT).body("A user is currently signed into this client.");
            }

            if(SessionController.getInstance().sessions.containsKey(uuid)) { // Check if the client is currently online with a session open
                SessionController.getInstance().sessions.remove(uuid); // Remove the session and it's token
                NectarServerApplication.getLogger().info("Revoked token for " + uuid + ": client deleted");
            }

            clients.deleteOne(Filters.eq("uuid", uuid)); // Delete client from the MongoDB database

            NectarServerApplication.getEventLog().logEntry(EventLog.EntryLevel.NOTICE, "Deleted client " + uuid + ", traced from " + request.getRemoteAddr());
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token expired/not valid.");
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).body("Success.");
    }

    protected static ResponseEntity checkUserAdmin(SessionToken token, MongoCollection<Document> users, Document doc) {
        // getString will throw an exception if the key is not present in the document
        String loggedInUser = doc.getString("loggedInUser");
        if(loggedInUser.equals("none")) {
            // No user is logged in
            throw new RuntimeException(); // Move to catch block
        }

        Document userDoc = users.find(Filters.eq("username", loggedInUser)).first();

        if(userDoc == null) { // We can't find the logged in user in the users database, strange
            NectarServerApplication.getLogger().warn("Failed to find logged in user \"" + loggedInUser + "\" for session "
                    + token.getUuid() + " while processing user registration"
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to find current logged in user in DB.");
        } else { // User found, check admin now
            if(!userDoc.getBoolean("admin", false)) {
                NectarServerApplication.getLogger().warn("ATTEMPTED CLIENT REGISTRATION BY NON_ADMIN USER \"" + loggedInUser + "\""
                        + " from session " + token.getUuid()
                );

                NectarServerApplication.getEventLog().addEntry(EventLog.EntryLevel.WARNING, "A non-admin user attempted to register a client from " + token.getUuid());
                throw new RuntimeException(); // Move to catch block
            }
            // User is confirmed logged in and admin, all checks passed.
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    protected static JSONObject registerClientToDatabase(String ip) {
        MongoCollection<Document> clients = NectarServerApplication.getDb().getCollection("clients");

        String uuid = UUID.randomUUID().toString();
        String authString = Util.generateNextRandomString();

        while(true) {
            if(clients.find(Filters.eq("uuid", uuid)).first() != null
                    || clients.find(Filters.eq("auth", Util.computeSHA512(authString))).first() != null) {
                // We have a collision of UUID or auth string, although it should be VERY VERY rare
                uuid = UUID.randomUUID().toString();
                authString = Util.generateNextRandomString();
            } else {
                // UUID and Auth string are unique, break out
                break;
            }
        }

        Document clientDoc = new Document()
                .append("uuid", uuid)
                .append("auth", Util.computeSHA512(authString))
                .append("registeredAt", System.currentTimeMillis())
                .append("registeredBy", ip);
        clients.insertOne(clientDoc);

        NectarServerApplication.getEventLog().logEntry(EventLog.EntryLevel.INFO, "Client registration success from " + ip + ", new client was registered: " + uuid);

        JSONObject root = new JSONObject();
        root.put("uuid", uuid);
        root.put("auth", authString);
        return root;
    }
}
