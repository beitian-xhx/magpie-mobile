package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderCreateDraftTest {
    private val secret = "sk-provider-regression-only-secret"
    private fun create(id: String = "custom-api", name: String = "Custom API", url: String = "https://api.example/v1/chat/completions",
        key: String = secret, models: List<String> = listOf("vendor/raw-model"), local: Boolean = false) =
        ProviderCreateDraft(id, name, url, key, models, local)
    private fun snapshot(models: List<String> = listOf("vendor/raw-model"), noModels: Boolean = false,
        id: String = "custom.api_old", revision: String = "a".repeat(64)) =
        ProviderEditSnapshot(id, revision, "Custom API", "https://api.example/v1/chat/completions", models, noModels,
            ProviderKeyMetadata(true, false, true))

    @Test fun creationIsExactlyCreateOnlySevenFieldsWithNewTrueAndRawModelIds() {
        val body = create().requestBody()
        assertEquals(setOf("new", "id", "name", "chat", "key", "models", "noModels"), body.keys)
        assertEquals(JsonPrimitive(true), body["new"])
        assertEquals(JsonPrimitive(secret), body["key"])
        assertEquals(JsonArray(listOf(JsonPrimitive("vendor/raw-model"))), body["models"])
        assertEquals(JsonPrimitive(false), body["noModels"])
        assertFalse(body.containsKey("revision"))
    }

    @Test fun emptyModelsExplicitlyDisableAllRatherThanCoreAutomaticAll() {
        val body = create(models = emptyList()).requestBody()
        assertEquals(JsonArray(emptyList()), body["models"])
        assertEquals(JsonPrimitive(true), body["noModels"])
    }

    @Test fun inputsTrimAndModelIdsDeduplicateButNeverUseDisplayNames() {
        val draft = create(id = " custom-api ", name = " 自定义 API ", key = " $secret ",
            models = listOf(" raw/id ", "raw/id", "vendor:model"))
        assertEquals("custom-api", draft.id)
        assertEquals("自定义 API", draft.name)
        assertEquals(listOf("raw/id", "vendor:model"), draft.modelIds)
        assertEquals(JsonPrimitive(secret), draft.requestBody()["key"])
    }

    @Test fun createIdUsesCoreHyphenSlugNotDotsOrUnderscoresOrTraversal() {
        for (id in listOf("", "HasUppercase", "custom.api", "custom_api", "../escape", "a/b", "a".repeat(65), "id\n")) {
            assertFailsWith<IllegalArgumentException> { create(id = id) }
        }
    }

    @Test fun namesEnforceRuneLimitAndRejectControlsWithoutEchoingInput() {
        create(name = "😀".repeat(120)).requestBody()
        for (name in listOf("", "  ", "😀".repeat(121), "name\r\n$secret")) {
            val error = assertFailsWith<IllegalArgumentException> { create(name = name) }
            assertFalse(error.stackTraceToString().contains(secret))
        }
    }

    @Test fun keysAreShortLivedSingleAsciiHeaderValuesAndNeverEnterToString() {
        for (key in listOf("", "  ", "x".repeat(8193), "sk-中文", "bad key", "bad\nkey")) {
            val error = assertFailsWith<IllegalArgumentException> { create(key = key).requestBody() }
            assertFalse(error.stackTraceToString().contains(key.takeIf { it.length > 8 } ?: secret))
        }
        val draft = create()
        assertFalse(draft.toString().contains(secret))
        assertFalse(mapOf("draft" to draft).toString().contains(secret))
        draft.clearSecret()
        assertFailsWith<IllegalArgumentException> { draft.requestBody() }
    }

    @Test fun urlRejectsUserinfoQueryFragmentControlsSchemesAndBadHosts() {
        for (url in listOf("https://$secret@api.example/chat", "https://api.example/chat?key=$secret",
            "https://api.example/chat#$secret", "https://api.example/\nchat", "https:/api.example", "https://api.example:",
            "https://api.example:0/chat", "https://api.example:65536/chat", "file:///tmp/key", "ftp://api.example/chat")) {
            val error = assertFailsWith<IllegalArgumentException> { create(url = url, local = true) }
            assertFalse(error.stackTraceToString().contains(secret))
        }
    }

    @Test fun plainHttpRequiresExplicitOptInAndExactLiteralLoopbackWithPort() {
        val good = "http://127.0.0.1:4567/v1/chat/completions"
        assertFailsWith<IllegalArgumentException> { create(url = good) }
        assertEquals(good, create(url = good, local = true).chatUrl)
        assertEquals("http://[::1]:4567/v1/chat/completions", create(url = "http://[::1]:4567/v1/chat/completions", local = true).chatUrl)
        for (url in listOf("http://127.0.0.1/chat", "http://localhost:4567/chat", "http://127.1:4567/chat",
            "http://2130706433:4567/chat", "http://127.0.0.1.evil:4567/chat", "http://192.168.1.1:4567/chat",
            "http://127.0.0.1:04567/chat", "http://[::1%25zone]:4567/chat", "http://evil@127.0.0.1:4567/chat")) {
            assertFailsWith<IllegalArgumentException> { create(url = url, local = true) }
        }
    }

    @Test fun httpsHostRulesRejectPrivateLiteralLocalSuffixSingleLabelAndEncodedPathWithoutDns() {
        for (url in listOf("https://127.0.0.1/chat", "https://10.0.0.1/chat", "https://172.16.1.1/chat", "https://192.168.1.1/chat",
            "https://169.254.1.1/chat", "https://[::1]/chat", "https://[fc00::1]/chat", "https://[fe80::1]/chat",
            "https://localhost/chat", "https://relay.local/chat", "https://relay.localhost/chat", "https://api.example./chat",
            "https://bad_host.example/chat", "https://api.example/%61pi/chat", "https://api.example/chat%3Fkey=secret")) {
            assertFailsWith<IllegalArgumentException> { create(url = url) }
        }
        assertEquals("https://8.8.8.8/v1/chat/completions", create(url = "https://8.8.8.8/v1/chat/completions").chatUrl)
        assertEquals("https://[2001:4860:4860::8888]/v1/chat/completions", create(url = "https://[2001:4860:4860::8888]/v1/chat/completions").chatUrl)
    }

    @Test fun modelLimitsCountRunesAndPreserveExplicitUnknownRawIds() {
        assertEquals(listOf("😀".repeat(256)), create(models = listOf("😀".repeat(256))).modelIds)
        for (models in listOf(listOf(""), listOf(" "), listOf("bad\nmodel"), listOf("😀".repeat(257)), List(1001) { "m$it" })) {
            assertFailsWith<IllegalArgumentException> { create(models = models) }
        }
    }

    @Test fun inputModelCollectionIsDefensivelyCopiedAndReadOnly() {
        val source = mutableListOf("raw/id")
        val draft = create(models = source)
        source.clear()
        assertEquals(listOf("raw/id"), draft.modelIds)
        assertFailsWith<UnsupportedOperationException> { (draft.modelIds as MutableList<String>).add("other") }
    }

    @Test fun nameOnlyEditOmitsAllUnchangedFieldsAndRetainsDotUnderscoreExistingId() {
        val body = ProviderEditDraft(snapshot(), name = "Renamed").requestBody()
        assertEquals(setOf("id", "revision", "name"), body.keys)
        assertEquals(JsonPrimitive("custom.api_old"), body["id"])
        assertFalse(body.containsKey("new"))
        assertFalse(body.containsKey("key"))
        assertFalse(body.containsKey("models"))
        assertFalse(body.containsKey("noModels"))
    }

    @Test fun blankEditKeyKeepsExistingKeyAndIsNotSentAsClearOrReplacement() {
        val body = ProviderEditDraft(snapshot(), name = "Renamed", apiKey = "   ").requestBody()
        assertFalse(body.containsKey("key"))
        assertEquals(setOf("id", "revision", "name"), body.keys)
    }

    @Test fun explicitEmptyEditedModelsDisableAllEvenIfSnapshotModelsAreEmptyAutomaticAll() {
        val body = ProviderEditDraft(snapshot(models = emptyList(), noModels = false), modelIds = emptyList()).requestBody()
        assertEquals(JsonArray(emptyList()), body["models"])
        assertEquals(JsonPrimitive(true), body["noModels"])
    }

    @Test fun untouchedModelsNullDoNotTurnAutomaticAllIntoSelectNoneOnNameChange() {
        val body = ProviderEditDraft(snapshot(models = emptyList(), noModels = false), name = "Renamed", modelIds = null).requestBody()
        assertEquals(setOf("id", "revision", "name"), body.keys)
    }

    @Test fun unchangedPreviouslyOptedInLoopbackEndpointIsNotRewrittenOnNameEdit() {
        val old = ProviderEditSnapshot("custom-api", "a".repeat(64), "Custom API", "http://127.0.0.1:4567/chat",
            listOf("raw/id"), false, ProviderKeyMetadata(true, false, true))
        val body = ProviderEditDraft(old, name = "Renamed", chatUrl = old.chat, allowLoopbackHttp = false).requestBody()
        assertEquals(setOf("id", "revision", "name"), body.keys)
        assertFailsWith<IllegalArgumentException> {
            ProviderEditDraft(old, chatUrl = "http://127.0.0.1:4568/chat", allowLoopbackHttp = false)
        }
    }

    @Test fun unknownSelectedModelIdsRemainUnlessUserExplicitlyChangesThem() {
        val old = snapshot(models = listOf("unlisted/future-model"))
        val nameOnly = ProviderEditDraft(old, name = "Renamed").requestBody()
        assertFalse(nameOnly.containsKey("models"))
        val changed = ProviderEditDraft(old, modelIds = listOf("unlisted/future-model", "new:raw-id")).requestBody()
        assertEquals(JsonArray(listOf(JsonPrimitive("unlisted/future-model"), JsonPrimitive("new:raw-id"))), changed["models"])
        assertEquals(JsonPrimitive(false), changed["noModels"])
    }

    @Test fun revisionOrSnapshotIdentityMissingAndNoDirtyFieldsRefuseWrite() {
        for (bad in listOf(snapshot(revision = ""), snapshot(revision = "opaque-but-not64hex"), snapshot(id = "../id"))) {
            assertFailsWith<ProviderEditPreconditionException> { ProviderEditDraft(bad, name = "Renamed").requestBody() }
        }
        assertFailsWith<ProviderEditPreconditionException> { ProviderEditDraft(snapshot()).requestBody() }
        assertFailsWith<ProviderEditPreconditionException> { ProviderEditDraft(snapshot(), name = "Custom API", modelIds = listOf("vendor/raw-model")).requestBody() }
    }

    @Test fun editKeyReplacementIsOnlyNonBlankDirtyKeyAndToStringAlwaysSafe() {
        val draft = ProviderEditDraft(snapshot(), apiKey = secret)
        assertEquals(setOf("id", "revision", "key"), draft.requestBody().keys)
        assertEquals(JsonPrimitive(secret), draft.requestBody()["key"])
        assertFalse(draft.toString().contains(secret))
        draft.clearSecret()
        assertFailsWith<ProviderEditPreconditionException> { draft.requestBody() }
    }

    @Test fun eachDraftSubmissionIsOneShotAndNeedsNewSnapshotAfterUnknown() {
        val create = create()
        create.submissionBody()
        assertFailsWith<IllegalStateException> { create.submissionBody() }
        val edit = ProviderEditDraft(snapshot(), name = "Renamed")
        edit.submissionBody()
        assertFailsWith<ProviderEditPreconditionException> { edit.submissionBody() }
    }
}
