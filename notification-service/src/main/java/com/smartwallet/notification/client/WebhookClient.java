package com.smartwallet.notification.client;

import com.smartwallet.notification.domain.NotificationPayload;

/**
 * Interface representing the webhook dispatcher client.
 * Using an interface allows safe and clean mocking in tests without needing
 * dynamic JVM agents or inline mock redefinitions on modern JDK versions.
 */
public interface WebhookClient {
    N8nWebhookClient.DispatchResult dispatch(NotificationPayload payload);
}
