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

import com.mongodb.Mongo;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Collation;
import io.github.jython234.nectar.server.struct.PeerInformation;
import lombok.Getter;
import org.bson.Document;
import org.ini4j.Ini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;
import java.io.IOException;

/**
 * Main Application class.
 *
 * @author jython234
 */
@SpringBootApplication
@EnableScheduling
public class NectarServerApplication {
    public static final String SOFTWARE = "Nectar-Server";
    public static final String SOFTWARE_VERSION = "0.1.0-SNAPSHOT";

    public static final int API_VERSION_MAJOR = 1;
    public static final int API_VERSION_MINOR = 3;
    public static final String ROOT_PATH = "/nectar/api/" + API_VERSION_MAJOR + "/" + API_VERSION_MINOR;

    public static final PeerInformation SERVER_INFORMATION = generateServerInfo();

    private static MongoClient mongoClient;
    @Getter private static MongoDatabase db;

    @Getter private static Logger logger;
    @Getter private static String configDir;
    @Getter private static NectarServerConfiguration configuration;

    public static void main(String[] args) {
        logger = LoggerFactory.getLogger("Nectar");

        try {
            System.out.println(Util.getResourceContents("header.txt"));
        } catch(IOException e) {
            // Don't worry about failing to print the header
        }

        logger.info("Starting " + SOFTWARE + " version " + SOFTWARE_VERSION +" implementing API "
                + API_VERSION_MAJOR + "-" + API_VERSION_MINOR);

        try {
            loadConfig();
        } catch (IOException e) {
            System.err.println("Failed to load configuration! IOException");
            e.printStackTrace(System.err);
            System.exit(1);
        }

        connectMongo();

        logger.info("Starting SpringApplication...");

        SpringApplication.run(NectarServerApplication.class, args);
    }

    private static void loadConfig() throws IOException {
        determineConfigDir();

        File configFile = new File(configDir + "/server.ini");
        if(!configFile.exists() || !configFile.isFile()) {
            Util.copyResourceTo("default.ini", configFile);
        }

        Ini conf = new Ini();
        conf.load(configFile);

        configuration = new NectarServerConfiguration(conf);
    }

    private static void determineConfigDir() {
        boolean useSystem = Boolean.parseBoolean(System.getenv("NECTAR_USE_SYSTEM"));
        if(useSystem) {
            configDir = "/etc/nectar-server/"; // TODO: Windows Support
        } else {
            configDir = System.getProperty("user.dir");
        }
    }

    private static void connectMongo() {
        mongoClient = new MongoClient(configuration.getDbIP(), configuration.getDbPort());
        db = mongoClient.getDatabase(configuration.getDbName());

        try {
            mongoClient.getAddress();
        } catch(Exception e) {
            e.printStackTrace();
            logger.error("Failed to connect to MongoDB database!");
            System.exit(1);
        }
    }

    private static PeerInformation generateServerInfo() {
        return new PeerInformation(
                SOFTWARE,
                SOFTWARE_VERSION,
                API_VERSION_MAJOR,
                API_VERSION_MINOR,
                new PeerInformation.SystemInfo(
                        System.getProperty("java.version"),
                        System.getenv("os.arch"),
                        System.getenv("os.name"),
                        "unknown", // TODO: Parse /proc/cpuinfo
                        Runtime.getRuntime().availableProcessors()
                )
        );
    }

}