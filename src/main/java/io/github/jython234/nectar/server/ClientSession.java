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
package io.github.jython234.nectar.server;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.jython234.nectar.server.controller.SessionController;
import io.github.jython234.nectar.server.struct.ClientState;
import io.github.jython234.nectar.server.struct.SessionToken;
import lombok.Getter;
import org.bson.Document;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * Represents a Client Session with
 * a token.
 *
 * @author jython234
 */
public class ClientSession {
    @Getter private SessionToken token;
    @Getter private ClientState state;
    @Getter private long lastPing;

    @Getter private int otherUpdates = -1;
    @Getter private int securityUpdates = -1;

    public ClientSession(SessionToken token) {
        this.token = token;

        this.state = ClientState.UNKNOWN;
        this.lastPing = System.currentTimeMillis();
    }

    public void updateState(ClientState state) {
        NectarServerApplication.getLogger().info("Client " + token.getUuid() + " state updated to: " + state.toString());
        this.state = state;

        MongoCollection<Document> clients = NectarServerApplication.getDb().getCollection("clients");
        clients.updateOne(Filters.eq("uuid", token.getUuid()),
                new Document("$set", new Document("state", state.toInt())));
    }

    public boolean handlePing(String dataRaw) {
        String payload = Util.getJWTPayload(dataRaw);

        JSONParser parser = new JSONParser();
        JSONObject obj;
        try {
            obj = (JSONObject) parser.parse(payload);
        } catch (ParseException e) {
            NectarServerApplication.getLogger().warn("Invalid JSON from client ping data from \"" + this.token.getUuid() + "\"");
            return false;
        }

        this.lastPing = System.currentTimeMillis();

        this.securityUpdates = (int) obj.get("securityUpdates");
        this.otherUpdates = (int) obj.get("otherUpdates");

        return true;
    }
}
