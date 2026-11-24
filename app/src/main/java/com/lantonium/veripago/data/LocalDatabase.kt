package com.lantonium.veripago.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val orderId: String,
    val orderCode: String,
    val merchantId: String,
    val customerUid: String?,
    val customerName: String,
    val customerPhone: String,
    val itemsJson: String,
    val totalAmount: Long,
    val checkoutChannel: String,
    val paymentStatus: String,
    val deliveryStatus: String,
    val matchedNotificationId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deliveredItemIndexesJson: String = "[]",
)

@Entity(
    tableName = "captured_notifications",
    indices = [Index(value = ["idempotencyHash"], unique = true)],
)
data class CapturedNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val idempotencyHash: String,
    val merchantId: String,
    val sourcePackage: String,
    val rawTextMasked: String,
    val extractedAmount: Long,
    val extractedSender: String,
    val status: String,
    val receivedAt: Long,
    val syncedAt: Long? = null,
)

@Entity(tableName = "bank_parser_rules")
data class BankRuleEntity(
    @PrimaryKey val ruleId: String,
    val walletName: String,
    val androidPackage: String,
    val amountRegex: String,
    val senderRegex: String,
    val isActive: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "pending_sync")
data class PendingSyncEntity(
    @PrimaryKey val operationId: String,
    val entityName: String,
    val entityId: String,
    val payloadJson: String,
    val createdAt: Long,
    val attempts: Int = 0,
)

@Entity(tableName = "merchant_products", primaryKeys = ["merchantId", "productId"])
data class MerchantProductEntity(
    val merchantId: String,
    val productId: String,
    val name: String,
    val enteredPrice: Long,
    val vatRate: Double,
    val priceIncludesVat: Boolean,
    val priceWithoutVat: Long,
    val vatAmount: Long,
    val priceWithVat: Long,
    val updatedAt: Long,
)

@Entity(tableName = "merchant_settings", primaryKeys = ["merchantId", "settingId"])
data class MerchantSettingEntity(
    val merchantId: String,
    val settingId: String,
    val payloadJson: String,
    val updatedAt: Long,
)

@Entity(tableName = "merchant_qrs", primaryKeys = ["merchantId", "provider"])
data class MerchantQrEntity(
    val merchantId: String,
    val provider: String,
    val contentType: String,
    val imageBase64: String,
    val paymentKeyLabel: String,
    val beneficiaryName: String,
    val updatedAt: Long,
)

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders WHERE merchantId = :merchantId ORDER BY createdAt DESC")
    fun observeMerchantOrders(merchantId: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE customerUid = :uid ORDER BY createdAt DESC")
    fun observeCustomerOrders(uid: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE paymentStatus = 'PENDING' AND totalAmount = :amount AND createdAt >= :earliest ORDER BY createdAt ASC")
    suspend fun pendingByAmount(amount: Long, earliest: Long): List<OrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(order: OrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(orders: List<OrderEntity>)

    @Query("SELECT * FROM orders WHERE orderId = :orderId LIMIT 1")
    suspend fun getById(orderId: String): OrderEntity?

    @Query("UPDATE orders SET paymentStatus = :status, matchedNotificationId = :notificationId, updatedAt = :updatedAt WHERE orderId = :orderId AND paymentStatus = 'PENDING'")
    suspend fun markPaid(orderId: String, status: String, notificationId: String, updatedAt: Long): Int

    @Query("UPDATE orders SET deliveryStatus = 'DELIVERED', updatedAt = :updatedAt WHERE orderId = :orderId")
    suspend fun markDelivered(orderId: String, updatedAt: Long)

    @Query("UPDATE orders SET deliveredItemIndexesJson = :indexesJson, updatedAt = :updatedAt WHERE orderId = :orderId")
    suspend fun updateDeliveredItemIndexes(orderId: String, indexesJson: String, updatedAt: Long)
}

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: CapturedNotificationEntity): Long

    @Query("UPDATE captured_notifications SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("SELECT * FROM captured_notifications ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<CapturedNotificationEntity>>
}

@Dao
interface BankRuleDao {
    @Query("SELECT * FROM bank_parser_rules WHERE isActive = 1")
    suspend fun getActive(): List<BankRuleEntity>

    @Query("SELECT * FROM bank_parser_rules WHERE isActive = 1")
    fun observeActive(): Flow<List<BankRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<BankRuleEntity>)
}

@Dao
interface PendingSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(operation: PendingSyncEntity)

    @Query("SELECT * FROM pending_sync ORDER BY createdAt ASC LIMIT :limit")
    suspend fun nextBatch(limit: Int = 50): List<PendingSyncEntity>

    @Query("DELETE FROM pending_sync WHERE operationId = :id")
    suspend fun acknowledge(id: String)

    @Query("UPDATE pending_sync SET attempts = attempts + 1 WHERE operationId = :id")
    suspend fun recordRetry(id: String)

    @Query("SELECT COUNT(*) FROM pending_sync WHERE entityName = :entityName AND entityId = :entityId")
    suspend fun hasPending(entityName: String, entityId: String): Int

    @Query("SELECT entityId FROM pending_sync WHERE entityName = :entityName AND entityId LIKE :idPrefix")
    suspend fun pendingIds(entityName: String, idPrefix: String): List<String>
}

@Dao
interface MerchantProductDao {
    @Query("SELECT * FROM merchant_products WHERE merchantId = :merchantId ORDER BY name COLLATE NOCASE")
    fun observe(merchantId: String): Flow<List<MerchantProductEntity>>

    @Query("SELECT * FROM merchant_products WHERE merchantId = :merchantId ORDER BY name COLLATE NOCASE")
    suspend fun getAll(merchantId: String): List<MerchantProductEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(product: MerchantProductEntity)

    @Query("DELETE FROM merchant_products WHERE merchantId = :merchantId AND productId = :productId")
    suspend fun delete(merchantId: String, productId: String)

    @Query("DELETE FROM merchant_products WHERE merchantId = :merchantId AND productId = :productId")
    suspend fun deleteRemote(merchantId: String, productId: String)
}

@Dao
interface MerchantSettingDao {
    @Query("SELECT * FROM merchant_settings WHERE merchantId = :merchantId AND settingId = :settingId LIMIT 1")
    suspend fun get(merchantId: String, settingId: String): MerchantSettingEntity?

    @Query("SELECT * FROM merchant_settings WHERE merchantId = :merchantId AND settingId = :settingId LIMIT 1")
    fun observe(merchantId: String, settingId: String): Flow<MerchantSettingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: MerchantSettingEntity)

    @Query("DELETE FROM merchant_settings WHERE merchantId = :merchantId AND settingId = :settingId")
    suspend fun delete(merchantId: String, settingId: String)
}

@Dao
interface MerchantQrDao {
    @Query("SELECT * FROM merchant_qrs WHERE merchantId = :merchantId ORDER BY provider")
    suspend fun getAll(merchantId: String): List<MerchantQrEntity>

    @Query("SELECT * FROM merchant_qrs WHERE merchantId = :merchantId AND provider = :provider LIMIT 1")
    suspend fun get(merchantId: String, provider: String): MerchantQrEntity?

    @Query("SELECT * FROM merchant_qrs WHERE merchantId = :merchantId ORDER BY provider")
    fun observe(merchantId: String): Flow<List<MerchantQrEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(qr: MerchantQrEntity)

    @Query("DELETE FROM merchant_qrs WHERE merchantId = :merchantId AND provider = :provider")
    suspend fun delete(merchantId: String, provider: String)
}

@Database(
    entities = [OrderEntity::class, CapturedNotificationEntity::class, BankRuleEntity::class, PendingSyncEntity::class,
        MerchantProductEntity::class, MerchantSettingEntity::class, MerchantQrEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class VeriPagoDatabase : RoomDatabase() {
    abstract fun orderDao(): OrderDao
    abstract fun notificationDao(): NotificationDao
    abstract fun bankRuleDao(): BankRuleDao
    abstract fun pendingSyncDao(): PendingSyncDao
    abstract fun merchantProductDao(): MerchantProductDao
    abstract fun merchantSettingDao(): MerchantSettingDao
    abstract fun merchantQrDao(): MerchantQrDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE orders ADD COLUMN deliveredItemIndexesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS merchant_products (merchantId TEXT NOT NULL, productId TEXT NOT NULL, name TEXT NOT NULL, enteredPrice INTEGER NOT NULL, vatRate REAL NOT NULL, priceIncludesVat INTEGER NOT NULL, priceWithoutVat INTEGER NOT NULL, vatAmount INTEGER NOT NULL, priceWithVat INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(merchantId, productId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS merchant_settings (merchantId TEXT NOT NULL, settingId TEXT NOT NULL, payloadJson TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(merchantId, settingId))")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS merchant_qrs (merchantId TEXT NOT NULL, provider TEXT NOT NULL, contentType TEXT NOT NULL, imageBase64 TEXT NOT NULL, paymentKeyLabel TEXT NOT NULL, beneficiaryName TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(merchantId, provider))")
            }
        }

        @Volatile private var INSTANCE: VeriPagoDatabase? = null
        fun get(context: Context): VeriPagoDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, VeriPagoDatabase::class.java, "veripago.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { INSTANCE = it }
        }
    }
}
