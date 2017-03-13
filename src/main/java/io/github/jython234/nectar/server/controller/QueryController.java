package io.github.jython234.nectar.server.controller;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.jython234.nectar.server.NectarServerApplication;
import io.github.jython234.nectar.server.Util;
import io.github.jython234.nectar.server.struct.ClientState;
import io.github.jython234.nectar.server.struct.ManagementSessionToken;
import org.bson.Document;
import org.json.simple.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller which handles queries for various
 * data.
 *
 * @author jython234
 */
@RestController
public class QueryController {

    @RequestMapping(NectarServerApplication.ROOT_PATH + "/query/queryState")
    public ResponseEntity<Integer> queryState(@RequestParam(value = "token") String jwtRaw,
                                              @RequestParam(value = "uuid") String uuid) {

        ManagementSessionToken token = ManagementSessionToken.fromJSON(Util.getJWTPayload(jwtRaw));
        if(token == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(-1);

        if(!SessionController.getInstance().checkManagementToken(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(-1);
        }

        if(SessionController.getInstance().sessions.containsKey(uuid)) {
            return ResponseEntity.ok(SessionController.getInstance().sessions.get(uuid).getState().toInt());
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

    public ResponseEntity queryClientUpdateCount(@RequestParam(value = "token") String jwtRaw,
                                                 @RequestParam(value = "uuid") String uuid) {

        ManagementSessionToken token = ManagementSessionToken.fromJSON(Util.getJWTPayload(jwtRaw));
        if(token == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid TOKENTYPE.");

        if(!SessionController.getInstance().checkManagementToken(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Token expired/not valid.");
        }

        JSONObject returnJSON = new JSONObject();

        if(SessionController.getInstance().sessions.containsKey(uuid)) {
            returnJSON.put("otherUpdates", SessionController.getInstance().sessions.get(uuid).getOtherUpdates());
            returnJSON.put("securityUpdates", SessionController.getInstance().sessions.get(uuid).getSecurityUpdates());

            return ResponseEntity.ok(returnJSON.toJSONString());
        }

        // The session requested is not connected. No data avaliable then.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Session not found or is not connected.");
    }
}