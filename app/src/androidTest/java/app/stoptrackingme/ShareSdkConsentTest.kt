package app.stoptrackingme

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareSdkConsentTest {
    @Test
    fun sdkConsentIsIndependentAndRevocable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val qqGranted = QQSdkConsent.isGranted(context)
        val weChatGranted = WeChatSdkConsent.isGranted(context)
        try {
            QQSdkConsent.revoke(context)
            WeChatSdkConsent.revoke(context)
            assertFalse(QQSdkConsent.isGranted(context))
            assertFalse(WeChatSdkConsent.isGranted(context))

            QQSdkConsent.grant(context)
            assertTrue(QQSdkConsent.isGranted(context))
            assertFalse(WeChatSdkConsent.isGranted(context))
            assertEquals(
                WeChatShare.Result.CONSENT_REQUIRED,
                WeChatShare.shareWebPageMessage(context, "https://example.com", "标题", "摘要", null,
                    WeChatShare.Destination.FRIEND),
            )

            WeChatSdkConsent.grant(context)
            QQSdkConsent.revoke(context)
            assertTrue(WeChatSdkConsent.isGranted(context))
            assertFalse(QQSdkConsent.isGranted(context))

            WeChatSdkConsent.revoke(context)
            assertEquals(
                WeChatShare.Result.CONSENT_REQUIRED,
                WeChatShare.shareWebPageMessage(context, "https://example.com", "标题", "摘要", null,
                    WeChatShare.Destination.TIMELINE),
            )
        } finally {
            if (qqGranted) QQSdkConsent.grant(context) else QQSdkConsent.revoke(context)
            if (weChatGranted) WeChatSdkConsent.grant(context) else WeChatSdkConsent.revoke(context)
        }
    }
}
