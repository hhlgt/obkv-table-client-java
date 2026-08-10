/*-
 * #%L
 * OBKV Table Client Framework
 * %%
 * Copyright (C) 2021 OceanBase
 * %%
 * OBKV Table Client Framework is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 * #L%
 */

package com.alipay.oceanbase.rpc.bolt.transport;

import com.alipay.oceanbase.rpc.bolt.protocol.ObTablePacket;
import com.alipay.oceanbase.rpc.protocol.packet.ObRpcPacketHeader;
import com.alipay.oceanbase.rpc.protocol.payload.AbstractPayload;
import com.alipay.oceanbase.rpc.protocol.payload.Pcodes;
import com.alipay.oceanbase.rpc.util.ObPureCrc32C;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Assert;
import org.junit.Test;

import static com.alipay.oceanbase.rpc.property.Property.RPC_RESPONSE_CHECKSUM_ENABLED;

public class ObTableRemotingChecksumTest {

    @Test
    public void testResponseChecksumDisabledByDefault() {
        Assert.assertFalse(RPC_RESPONSE_CHECKSUM_ENABLED.getDefaultBoolean());

        ByteBuf content = Unpooled.wrappedBuffer(new byte[] { 1, 2, 3, 4 });
        try {
            int readerIndex = content.readerIndex();
            ObTableRemoting remoting = new ObTableRemoting(new ObPacketFactory(false));

            Assert.assertTrue(remoting.isResponseChecksumValid(content, Long.MAX_VALUE));
            Assert.assertEquals(readerIndex, content.readerIndex());
        } finally {
            content.release();
        }
    }

    @Test
    public void testResponseChecksumEnabled() {
        byte[] bytes = new byte[] { 1, 2, 3, 4 };
        ByteBuf content = Unpooled.wrappedBuffer(bytes);
        try {
            int readerIndex = content.readerIndex();
            ObTableRemoting remoting = new ObTableRemoting(new ObPacketFactory(false), true);
            long checksum = ObPureCrc32C.calculate(bytes);

            Assert.assertTrue(remoting.isResponseChecksumValid(content, checksum));
            Assert.assertFalse(remoting.isResponseChecksumValid(content, checksum + 1));
            Assert.assertEquals(readerIndex, content.readerIndex());
        } finally {
            content.release();
        }
    }

    @Test
    public void testRequestChecksumRemainsEnabled() {
        byte[] payloadContent = new byte[] { 5, 6, 7, 8 };
        FixedPayload payload = new FixedPayload(payloadContent);
        ObTablePacket packet = new ObPacketFactory(false).createRequestCommand(payload);
        ByteBuf packetContent = Unpooled.wrappedBuffer(packet.getPacketContent());
        try {
            ObRpcPacketHeader header = new ObRpcPacketHeader();
            header.decode(packetContent);

            Assert.assertEquals(0x8762fcd6L, header.getChecksum());
        } finally {
            packetContent.release();
        }
    }

    private static class FixedPayload extends AbstractPayload {
        private final byte[] content;

        FixedPayload(byte[] content) {
            this.content = content;
        }

        @Override
        public int getPcode() {
            return Pcodes.OB_TABLE_API_LOGIN;
        }

        @Override
        public byte[] encode() {
            return content;
        }

        @Override
        public long getPayloadContentSize() {
            return content.length;
        }
    }
}
