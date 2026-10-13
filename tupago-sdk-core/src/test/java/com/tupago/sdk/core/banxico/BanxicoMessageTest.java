package com.tupago.sdk.core.banxico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BanxicoMessageTest {

    @Test
    void extractsKnownFieldsFromCreateResponse() {
        String json = "{\"TYP\":1,\"v\":{\"DEV\":\"DEV1\"},\"ic\":{\"IDC\":\"IDC-42\",\"SER\":3,\"ENC\":\"ENC1\"}}";
        BanxicoMessage m = BanxicoMessage.fromJson(json);
        assertThat(m.icIdc()).isEqualTo("IDC-42");
        assertThat(m.typ()).isEqualTo(1);
        assertThat(m.deviceDev()).isEqualTo("DEV1");
        assertThat(m.icSer()).isEqualTo(3);
        assertThat(m.icEnc()).isEqualTo("ENC1");
    }

    @Test
    void preservesUnknownFieldsRoundTrip() {
        String json = "{\"ic\":{\"IDC\":\"X\"},\"TYP\":2,\"EXTRA_TOP\":42,\"ic\":{\"UNKNOWN_SUB\":\"u\"}}";
        // (duplicate "ic" key is legal JSON; parser keeps last — just assert preservation of provided bytes)
        BanxicoMessage m = BanxicoMessage.fromJson("{\"ic\":{\"IDC\":\"X\",\"UNKNOWN_SUB\":\"u\"},\"TYP\":2,\"EXTRA_TOP\":42}");
        assertThat(m.icIdc()).isEqualTo("X");
        assertThat(m.rawJson()).contains("UNKNOWN_SUB");
        assertThat(m.rawJson()).contains("EXTRA_TOP");
        // Unknown fields survive in the tree.
        assertThat(m.raw().path("EXTRA_TOP").asInt()).isEqualTo(42);
        assertThat(m.raw().path("ic").path("UNKNOWN_SUB").asText()).isEqualTo("u");
    }

    @Test
    void parsesTransactionsFromTopLevelArray() {
        String json = "[{\"id\":\"t1\",\"cc\":\"01\",\"mt\":100.5,\"hs\":1,\"hp\":2,\"e\":3,\"cr\":\"OK\"}]";
        BanxicoMessage m = BanxicoMessage.fromJson(json);
        assertThat(m.transactions()).hasSize(1);
        BanxicoTransaction t = m.transactions().get(0);
        assertThat(t.id()).isEqualTo("t1");
        assertThat(t.cc()).isEqualTo("01");
        assertThat(t.mt()).isEqualTo(100.5);
        assertThat(t.estado()).isEqualTo(3);
        assertThat(t.cr()).isEqualTo("OK");
    }

    @Test
    void parsesTransactionsFromObjectField() {
        String json = "{\"transactions\":[{\"id\":\"t2\",\"e\":5}]}";
        BanxicoMessage m = BanxicoMessage.fromJson(json);
        assertThat(m.transactions()).hasSize(1);
        assertThat(m.transactions().get(0).id()).isEqualTo("t2");
        assertThat(m.transactions().get(0).estado()).isEqualTo(5);
    }

    @Test
    void invalidJson_throws() {
        assertThatThrownBy(() -> BanxicoMessage.fromJson("{not json"))
                .isInstanceOf(BanxicoPayloadException.class);
    }

    @Test
    void fromJsonNode_preservesStructure() {
        BanxicoMessage original = BanxicoMessage.fromJson("{\"ic\":{\"IDC\":\"Z\"},\"NEW\":[1,2,3]}");
        BanxicoMessage copy = BanxicoMessage.fromJsonNode(original.raw());
        assertThat(copy.icIdc()).isEqualTo("Z");
        assertThat(copy.raw().path("NEW").size()).isEqualTo(3);
    }
}
