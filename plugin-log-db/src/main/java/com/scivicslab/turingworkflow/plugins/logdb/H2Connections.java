/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.turingworkflow.plugins.logdb;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens H2 connections through the H2 driver class this plugin's own class loader sees.
 *
 * <p>{@code DriverManager} hands a connection only to drivers visible to the caller's class loader, so it finds no
 * driver when H2 was loaded by another class loader ({@code loader.loadJar} or {@code plugins.yaml}). Calling the
 * driver directly works there and on a plain classpath.</p>
 */
final class H2Connections {

    private H2Connections() {
    }

    /**
     * Opens a connection to an H2 database.
     *
     * @param url an H2 JDBC URL
     * @return the open connection
     * @throws SQLException if the URL is not an H2 URL or the database cannot be opened
     */
    static Connection open(String url) throws SQLException {
        Connection connection = new org.h2.Driver().connect(url, new Properties());
        if (connection == null) {
            throw new SQLException("Not an H2 JDBC URL: " + url);
        }
        return connection;
    }
}
