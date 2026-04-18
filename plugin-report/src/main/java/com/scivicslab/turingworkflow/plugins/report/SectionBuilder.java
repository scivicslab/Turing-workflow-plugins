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

package com.scivicslab.turingworkflow.plugins.report;

/**
 * POJO interface for section builders.
 *
 * <p>This is a pure POJO interface - it does NOT extend {@code CallableByActionName}.
 * Each section builder POJO should be wrapped by a corresponding Actor class that
 * exposes actions via {@code @Action} annotations.</p>
 *
 * <h2>Design principle:</h2>
 * <ul>
 *   <li>POJO contains business logic only</li>
 *   <li>Actor handles String argument parsing and action dispatch</li>
 *   <li>This separation enables distributed actor messaging (messages are always strings)</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public interface SectionBuilder {

    /**
     * Generates the section content.
     *
     * @return the section content as a string
     */
    String generate();

    /**
     * Returns the section title.
     *
     * <p>If null or empty, the section content is output without a title line.</p>
     *
     * @return the section title, or null for no title
     */
    default String getTitle() {
        return null;
    }
}
