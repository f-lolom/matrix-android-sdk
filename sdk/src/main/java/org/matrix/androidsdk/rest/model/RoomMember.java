/*
 * Copyright 2014 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.androidsdk.rest.model;

/**
 * Class representing a room member: a user with membership information.
 */
public class RoomMember {
    public static final String MEMBERSHIP_JOIN = "join";
    public static final String MEMBERSHIP_INVITE = "invite";
    public static final String MEMBERSHIP_LEAVE = "leave";
    public static final String MEMBERSHIP_BAN = "ban";

    public String displayname;
    public String avatarUrl;
    public String membership;

    private User user;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }
}