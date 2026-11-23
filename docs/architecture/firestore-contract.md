# VeriPago Firestore and SQLite contract (v1 alpha)

All timestamps are Firestore `Timestamp` values in UTC. Money is an integer number of Colombian pesos; do not use floating point. Client-authored records include `createdAt`/`localUpdatedAt` and server writes set `serverUpdatedAt` with `serverTimestamp()`.

## Collections

### `users/{uid}`

`uid`, `email`, `displayName`, `photoUrl`, `role` (`ADMINISTRADOR | COMERCIANTE | USUARIO`), `isActive`, `quotaMaxLinks`, `loyaltyPoints`, `createdAt`, `lastLoginAt`. A new Google-authenticated client may create only its own initial `COMERCIANTE` or `USUARIO` profile. Only an administrator may assign administrator role or change another user's role.

### `payment_links/{linkId}`

`merchantId`, `slug`, `title`, `defaultMode` (`IN_SITU_QR | WHATSAPP_LINK`), `schemaJson`, `qrImageUrl`, `paymentKeyLabel`, `beneficiaryName`, `reconciliationWindowMinutes`, `isActive`, `createdAt`. `slug` is normalized lowercase and unique; Firestore has no unique constraint, so production creation must reserve it transactionally through a `slugs/{slug}` record (server endpoint). Public reads expose only active links. Avoid storing private customer data here.

### `orders/{orderId}`

`linkId`, `merchantId`, `orderCode`, `customerUid` (nullable), `customerName`, `customerPhone`, `senderAccountName` (nullable), `items` (`[{name, qty, unitPrice, subtotal}]`), `totalAmount` (integer COP), `checkoutChannel` (`MOBILE_IN_SITU_QR | WEB_IN_SITU_QR | WHATSAPP_LINK`), `paymentStatus` (`PENDING | VERIFIED_AUTO | VERIFIED_MANUAL | CANCELLED`), `deliveryStatus` (`PENDING_DELIVERY | DELIVERED`), `matchedNotificationId` (nullable), `createdAt`, `localUpdatedAt`, `serverUpdatedAt`.

Orders are private to the owning merchant and the signed-in customer. The public checkout must not read this document. Create a `checkout_sessions/{randomSessionId}` containing only `{orderId, totalAmount, paymentStatus, deliveryStatus, updatedAt, expiresAt}` and expose it as a random bearer capability; trusted server code mirrors allowed status changes. This avoids exposing name, phone, and item details to anyone who can guess or share an order ID. Anonymous order creation and session provisioning must go through a rate-limited callable/HTTP function; never grant arbitrary client writes to orders.

### `payment_notifications/{notifId}`

`merchantId`, `deviceId`, `idempotencyHash` (SHA-256), `sourcePackage`, `rawTextMasked`, `extractedAmount` (integer COP), `extractedSender`, `status` (`MATCHED | UNMATCHED | DUPLICATE`), `receivedAt`, `syncedAt`. Use `idempotencyHash` as the deterministic document ID or enforce it in a transaction; never upload unmasked notification contents.

### `bank_parser_rules/{ruleId}`

`walletName`, `androidPackage`, `amountRegex`, `senderRegex`, `isActive`, `updatedAt`. The seed values are examples and must be validated against current bank notification formats before enabling automatic matching.

### `linked_devices/{deviceId}`

`merchantId`, `deviceName`, `appVersion`, `listenerPermissionGranted`, `batteryLevel`, `lastHeartbeatAt`.

### `platform_settings/global`, `announcements/{announcementId}`

Settings hold platform limits and APK metadata (`latestApkUrl`, `latestApkVersion`, `apkReleaseNotes`). Announcements contain `title`, `body`, `isPublished`, `startsAt`, `endsAt`, and `createdAt`.

### `audit_logs/{logId}`

Append-only server records: `actorUid`, `actorRole`, `action`, `entityType`, `entityId`, `beforeData`, `afterData`, `timestamp`. Client rules deny all writes, including administrator writes. Trusted server code must write using least-privilege service credentials.

## SQLite / Room mapping

| Room table | Key fields | Firestore source | Offline behavior |
| --- | --- | --- | --- |
| `OrderEntity` | `orderId`, `orderCode`, `customerName`, `customerPhone`, `itemsJson`, `totalAmount`, `checkoutChannel`, `paymentStatus`, `deliveryStatus`, `createdAt`, `updatedAt` | `orders` | Keep merchant's active orders; queue local status updates in `PendingSyncEntity`. |
| `CapturedNotificationEntity` | `id`, unique `idempotencyHash`, package, masked text, amount, sender, status, receivedAt | `payment_notifications` | Insert idempotently before matching; sync later. |
| `BankRuleEntity` | `ruleId`, wallet, package, amount regex, sender regex, active, updatedAt | `bank_parser_rules` | Keep last known active rules for offline parsing. |
| `PendingSyncEntity` | operationId, entity, entityId, payloadJson, createdAt, attempts | Outbox for orders/notifications/devices | Retry with WorkManager on `NetworkType.CONNECTED`; make each operation idempotent. |

## Reconciliation protocol

1. Persist each captured notification in Room with a SHA-256 idempotency key before any side effect. The key should include package, normalized amount, and a stable notification time bucket; do not use a coarse bucket that can collapse legitimate same-value transfers.
2. In one Room transaction, select pending orders for the same merchant and amount within their reconciliation window. Rank sender-name similarity first, then oldest order; if there is no plausible sender match, use oldest eligible order. Mark exactly one order and notification as matched and append outbox operations atomically.
3. When connected, upload via an idempotent server transaction: verify the notification key is unused, verify the selected order is still pending and owned by the merchant, then atomically set its status and matched notification. If another device won the race, preserve the notification as unmatched for manual review.
4. Firestore snapshot listeners feed remote orders and rule updates into Room using upserts. Local writes update Room immediately and are retried from the outbox by WorkManager. Never mark delivery as paid; payment and delivery states remain independent.

## Security and operational notes

- Google provider authentication is required for account data. The initial profile cannot self-assign `ADMINISTRADOR`.
- Public checkout uses a minimal, expiring, random-ID session; Firestore rules cannot redact fields from a document, so a public reader must never be granted access to `orders`.
- Firestore client rules cannot enforce uniqueness of `slug` or atomic order reconciliation across arbitrary queries; use trusted server transactions for both.
- QR files and APK releases are public-readable by product requirement. Do not commit QR images containing account identifiers into a public Git repository.
- Admin access based on a mutable Firestore role is appropriate only if role changes are tightly guarded; production hardening should also move administrator authorization to Firebase custom claims managed by trusted server code.
