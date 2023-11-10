/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bson.codecs.kotlinx

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeDecoder.Companion.DECODE_DONE
import kotlinx.serialization.encoding.CompositeDecoder.Companion.UNKNOWN_NAME
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.modules.SerializersModule
import org.bson.AbstractBsonReader
import org.bson.BsonBinarySubType
import org.bson.BsonInvalidOperationException
import org.bson.BsonReader
import org.bson.BsonType
import org.bson.BsonValue
import org.bson.UuidRepresentation
import org.bson.codecs.BsonValueCodec
import org.bson.codecs.DecoderContext
import org.bson.codecs.UuidCodec
import org.bson.internal.UuidHelper
import org.bson.types.ObjectId
import java.util.*

/**
 * The BsonDecoder interface
 *
 * For custom serialization handlers
 */
public sealed interface BsonDecoder {

    /** @return the decoded ObjectId */
    public fun decodeObjectId(): ObjectId
    /** @return the decoded BsonValue */
    public fun decodeBsonValue(): BsonValue
    /** @return the decoded UUID */
    public fun decodeUuid(): UUID

    /** @return the BsonReader */
    public fun reader(): BsonReader
}

@ExperimentalSerializationApi
internal open class DefaultBsonDecoder(
    internal val reader: AbstractBsonReader,
    override val serializersModule: SerializersModule,
    internal val configuration: BsonConfiguration
) : BsonDecoder, JsonDecoder, AbstractDecoder() {

    override val json = Json {
        explicitNulls = configuration.explicitNulls
        encodeDefaults = configuration.encodeDefaults
        classDiscriminator = configuration.classDiscriminator
        serializersModule = this@DefaultBsonDecoder.serializersModule
    }

    private data class ElementMetadata(val name: String, val nullable: Boolean, var processed: Boolean = false)
    private var elementsMetadata: Array<ElementMetadata>? = null
    private var currentIndex: Int = UNKNOWN_INDEX

    companion object {
        val validKeyKinds = setOf(PrimitiveKind.STRING, PrimitiveKind.CHAR, SerialKind.ENUM)
        val bsonValueCodec = BsonValueCodec()
        val uuidCodec = UuidCodec(UuidRepresentation.STANDARD)
        const val UNKNOWN_INDEX = -10
    }

    private fun initElementMetadata(descriptor: SerialDescriptor) {
        if (this.elementsMetadata != null) return
        val elementsMetadata =
            Array(descriptor.elementsCount) {
                val elementDescriptor = descriptor.getElementDescriptor(it)
                ElementMetadata(
                    elementDescriptor.serialName, elementDescriptor.isNullable && !descriptor.isElementOptional(it))
            }
        this.elementsMetadata = elementsMetadata
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        initElementMetadata(descriptor)
        currentIndex = decodeElementIndexImpl(descriptor)
        elementsMetadata?.getOrNull(currentIndex)?.processed = true
        return currentIndex
    }

    @Suppress("ReturnCount", "ComplexMethod")
    private fun decodeElementIndexImpl(descriptor: SerialDescriptor): Int {
        val elementMetadata = elementsMetadata ?: error("elementsMetadata may not be null.")
        val name: String? =
            when (reader.state ?: error("State of reader may not be null.")) {
                AbstractBsonReader.State.NAME -> reader.readName()
                AbstractBsonReader.State.VALUE -> reader.currentName
                AbstractBsonReader.State.TYPE -> {
                    reader.readBsonType()
                    return decodeElementIndexImpl(descriptor)
                }
                AbstractBsonReader.State.END_OF_DOCUMENT,
                AbstractBsonReader.State.END_OF_ARRAY ->
                    return elementMetadata.indexOfFirst { it.nullable && !it.processed }
                else -> null
            }

        return name?.let {
            val index = descriptor.getElementIndex(it)
            return if (index == UNKNOWN_NAME) {
                reader.skipValue()
                decodeElementIndexImpl(descriptor)
            } else {
                index
            }
        }
            ?: UNKNOWN_NAME
    }

    @Suppress("ReturnCount")
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        when (descriptor.kind) {
            is StructureKind.LIST -> {
                reader.readStartArray()
                return BsonArrayDecoder(reader, serializersModule, configuration)
            }
            is PolymorphicKind -> {
                reader.readStartDocument()
                return PolymorphicDecoder(reader, serializersModule, configuration)
            }
            is StructureKind.CLASS,
            StructureKind.OBJECT -> {
                val current = reader.currentBsonType
                if (current == null || current == BsonType.DOCUMENT) {
                    reader.readStartDocument()
                }
            }
            is StructureKind.MAP -> {
                reader.readStartDocument()
                return BsonDocumentDecoder(reader, serializersModule, configuration)
            }
            else -> throw SerializationException("Primitives are not supported at top-level")
        }
        return DefaultBsonDecoder(reader, serializersModule, configuration)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        when (descriptor.kind) {
            is StructureKind.LIST -> reader.readEndArray()
            is StructureKind.MAP,
            StructureKind.CLASS,
            StructureKind.OBJECT -> reader.readEndDocument()
            else -> super.endStructure(descriptor)
        }
    }

    override fun decodeByte(): Byte = decodeInt().toByte()
    override fun decodeChar(): Char = decodeString().single()
    override fun decodeFloat(): Float = decodeDouble().toFloat()
    override fun decodeShort(): Short = decodeInt().toShort()
    override fun decodeBoolean(): Boolean = readOrThrow({ reader.readBoolean() }, BsonType.BOOLEAN)
    override fun decodeDouble(): Double = readOrThrow({ reader.readDouble() }, BsonType.DOUBLE)
    override fun decodeInt(): Int = readOrThrow({ reader.readInt32() }, BsonType.INT32)
    override fun decodeLong(): Long = readOrThrow({ reader.readInt64() }, BsonType.INT64)
    override fun decodeString(): String = readOrThrow({ reader.readString() }, BsonType.STRING)

    override fun decodeNull(): Nothing? {
        if (reader.state == AbstractBsonReader.State.VALUE) {
            readOrThrow({ reader.readNull() }, BsonType.NULL)
        }
        return null
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = enumDescriptor.getElementIndex(decodeString())
    override fun decodeNotNullMark(): Boolean {
        return reader.state != AbstractBsonReader.State.END_OF_DOCUMENT && reader.currentBsonType != BsonType.NULL
    }

    override fun decodeObjectId(): ObjectId = readOrThrow({ reader.readObjectId() }, BsonType.OBJECT_ID)
    override fun decodeBsonValue(): BsonValue = bsonValueCodec.decode(reader, DecoderContext.builder().build())
    override fun decodeUuid(): UUID = uuidCodec.decode(reader, DecoderContext.builder().build())

    @Suppress("ComplexMethod")
    override fun decodeJsonElement(): JsonElement = reader.run {

        if (state == AbstractBsonReader.State.INITIAL ||
            state == AbstractBsonReader.State.SCOPE_DOCUMENT ||
            state == AbstractBsonReader.State.TYPE) {
            readBsonType()
        }

        if (state == AbstractBsonReader.State.NAME) {
            // ignore name
            skipName()
        }

        // @formatter:off
        return when (currentBsonType) {
            BsonType.DOCUMENT -> readJsonObject()
            BsonType.ARRAY -> readJsonArray()
            BsonType.NULL -> JsonPrimitive(decodeNull())
            BsonType.STRING -> JsonPrimitive(decodeString())
            BsonType.BOOLEAN -> JsonPrimitive(decodeBoolean())
            BsonType.INT32 -> JsonPrimitive(decodeInt())
            BsonType.INT64 -> JsonPrimitive(decodeLong())
            BsonType.DOUBLE -> JsonPrimitive(decodeDouble())
            BsonType.DECIMAL128 -> JsonPrimitive(reader.readDecimal128())
            BsonType.OBJECT_ID -> JsonPrimitive(decodeObjectId().toHexString())
            BsonType.DATE_TIME -> JsonPrimitive(reader.readDateTime())
            BsonType.TIMESTAMP -> JsonPrimitive(reader.readTimestamp().value)
            BsonType.BINARY -> {
                val subtype = reader.peekBinarySubType()
                val data = reader.readBinaryData().data
                when (subtype) {
                    BsonBinarySubType.UUID_LEGACY.value -> JsonPrimitive(
                        UuidHelper.decodeBinaryToUuid(
                            data, subtype,
                            UuidRepresentation.JAVA_LEGACY
                        ).toString()
                    )
                    BsonBinarySubType.UUID_STANDARD.value -> JsonPrimitive(
                        UuidHelper.decodeBinaryToUuid(
                            data, subtype,
                            UuidRepresentation.STANDARD
                        ).toString()
                    )
                    else -> JsonPrimitive(Base64.getEncoder().encodeToString(data))
                }
            }
            else -> error("unsupported json type: $currentBsonType")
        }
        // @formatter:on
    }

    override fun reader(): BsonReader = reader

    private fun readJsonObject(): JsonObject {

        reader.readStartDocument()
        val obj = buildJsonObject {
            var type = reader.readBsonType()
            while (type != BsonType.END_OF_DOCUMENT) {
                put(reader.readName(), decodeJsonElement())
                type = reader.readBsonType()
            }
        }

        reader.readEndDocument()
        return obj
    }

    private fun readJsonArray(): JsonArray {

        reader.readStartArray()
        val array = buildJsonArray {
            var type = reader.readBsonType()
            while (type != BsonType.END_OF_DOCUMENT) {
                add(decodeJsonElement())
                type = reader.readBsonType()
            }
        }

        reader.readEndArray()
        return array
    }

    private inline fun <T> readOrThrow(action: () -> T, bsonType: BsonType): T {
        return try {
            action()
        } catch (e: BsonInvalidOperationException) {
            throw BsonInvalidOperationException(
                "Reading field '${reader.currentName}' failed expected $bsonType type but found:" +
                    " ${reader.currentBsonType}.",
                e)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class BsonArrayDecoder(
    reader: AbstractBsonReader,
    serializersModule: SerializersModule,
    configuration: BsonConfiguration
) : DefaultBsonDecoder(reader, serializersModule, configuration) {
    private var index = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val nextType = reader.readBsonType()
        if (nextType == BsonType.END_OF_DOCUMENT) return DECODE_DONE
        return index++
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class PolymorphicDecoder(
    reader: AbstractBsonReader,
    serializersModule: SerializersModule,
    configuration: BsonConfiguration
) : DefaultBsonDecoder(reader, serializersModule, configuration) {
    private var index = 0

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T =
        deserializer.deserialize(DefaultBsonDecoder(reader, serializersModule, configuration))

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return when (index) {
            0 -> index++
            1 -> index++
            else -> DECODE_DONE
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class BsonDocumentDecoder(
    reader: AbstractBsonReader,
    serializersModule: SerializersModule,
    configuration: BsonConfiguration
) : DefaultBsonDecoder(reader, serializersModule, configuration) {

    private var index = 0
    private var isKey = false

    override fun decodeString(): String {
        return if (isKey) {
            reader.readName()
        } else {
            super.decodeString()
        }
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val keyKind = descriptor.getElementDescriptor(0).kind
        if (!validKeyKinds.contains(keyKind)) {
            throw SerializationException(
                "Invalid key type for ${descriptor.serialName}. Expected STRING or ENUM but found: `${keyKind}`")
        }

        if (!isKey) {
            isKey = true
            val nextType = reader.readBsonType()
            if (nextType == BsonType.END_OF_DOCUMENT) return DECODE_DONE
        } else {
            isKey = false
        }
        return index++
    }
}
