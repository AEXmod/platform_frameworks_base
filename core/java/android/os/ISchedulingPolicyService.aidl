/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

/**
 * Initially only provides requestPriority() below, but in longer term
 * other scheduling policy related services will be collected here.
 *
 * @hide
 */
interface ISchedulingPolicyService {

    /**
     * Move thread tid into appropriate cgroup and assign it priority prio.
     * The thread group leader of tid must be pid.
     * There may be restrictions on who can call this.
     */
    int requestPriority(int pid, int tid, int prio, boolean isForApp);
    int requestPriorityDL(int pid, int tid,
        long runtime, long deadline, long period, boolean isForApp);

}
