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

    @Query("UPDATE orders SET paymentStatus = :status, matchedNotificationId = :notificationId, updatedAt = :updatedAt WHERE orderId = :orderId AND paymentStatus = 'PENDING'")
    suspend fun markPaid(orderId: String, status: String, notificationId: String, updatedAt: Long): Int

    @Query("UPDATE orders SET deliveryStatus = 'DELIVERED', updatedAt = :updatedAt WHERE orderId = :orderId")
    suspend fun markDelivered(orderId: String, updatedAt: Long)
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
}

@Database(
    entities = [OrderEntity::class, CapturedNotificationEntity::class, BankRuleEntity::class, PendingSyncEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VeriPagoDatabase : RoomDatabase() {
    abstract fun orderDao(): OrderDao
    abstract fun notificationDao(): NotificationDao
    abstract fun bankRuleDao(): BankRuleDao
    abstract fun pendingSyncDao(): PendingSyncDao

    companion object {
        @Volatile private var INSTANCE: VeriPagoDatabase? = null
        fun get(context: Context): VeriPagoDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, VeriPagoDatabase::class.java, "veripago.db")
                .build().also { INSTANCE = it }
        }
    }
}
