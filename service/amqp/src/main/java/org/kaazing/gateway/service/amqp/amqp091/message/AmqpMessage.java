/**
 * Copyright 2007-2015, Kaazing Corporation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kaazing.gateway.service.amqp.amqp091.message;

import org.apache.mina.core.filterchain.IoFilter.NextFilter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.kaazing.gateway.service.amqp.amqp091.filter.AmqpFilter;
import org.kaazing.gateway.transport.bridge.Message;

public abstract class AmqpMessage extends Message {
    public enum MessageKind {
        PROTOCOL_HEADER,
        CLASS
    }
    
    public abstract MessageKind getMessageKind();
    
    public abstract <S extends IoSession> void messageReceived(AmqpFilter<S> filter, 
                                                               NextFilter    nextFilter, 
                                                               S             session)
           throws Exception;

    public abstract <S extends IoSession> void filterWrite(AmqpFilter<S> filter,
                                                           NextFilter    nextFilter, 
                                                           S             session, 
                                                           WriteRequest  writeRequest) 
           throws Exception;

    public abstract <S extends IoSession> void messageSent(AmqpFilter<S> filter, 
                                                           NextFilter    nextFilter, 
                                                           S             session, 
                                                           WriteRequest  writeRequest) 
           throws Exception;
}
