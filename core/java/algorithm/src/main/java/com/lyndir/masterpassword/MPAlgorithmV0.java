//==============================================================================
// This file is part of Master Password.
// Copyright (c) 2011-2017, Maarten Billemont.
//
// Master Password is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// Master Password is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You can find a copy of the GNU General Public License in the
// LICENSE file.  Alternatively, see <http://www.gnu.org/licenses/>.
//==============================================================================

package com.lyndir.masterpassword;

import com.google.common.base.*;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.UnsignedInteger;
import com.lambdaworks.crypto.SCrypt;
import com.lyndir.lhunath.opal.crypto.CryptUtils;
import com.lyndir.lhunath.opal.system.*;
import com.lyndir.lhunath.opal.system.logging.Logger;
import com.lyndir.lhunath.opal.system.util.ConversionUtils;
import java.nio.*;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;


/**
 * @author lhunath, 2014-08-30
 * @see MPMasterKey.Version#V0
 */
@SuppressWarnings("NewMethodNamingConvention")
public class MPAlgorithmV0 extends MPAlgorithm {

    public final MPMasterKey.Version version = MPMasterKey.Version.V0;

    protected final Logger logger = Logger.get( getClass() );

    @Override
    public byte[] masterKey(final String fullName, final char[] masterPassword) {

        byte[] fullNameBytes       = fullName.getBytes( mpw_charset() );
        byte[] fullNameLengthBytes = toBytes( fullName.length() );

        String keyScope = MPKeyPurpose.Authentication.getScope();
        logger.trc( "keyScope: %s", keyScope );

        // Calculate the master key salt.
        logger.trc( "masterKeySalt: keyScope=%s | #fullName=%s | fullName=%s",
                    keyScope, CodeUtils.encodeHex( fullNameLengthBytes ), fullName );
        byte[] masterKeySalt = Bytes.concat( keyScope.getBytes( mpw_charset() ), fullNameLengthBytes, fullNameBytes );
        logger.trc( "  => masterKeySalt.id: %s", CodeUtils.encodeHex( toID( masterKeySalt ) ) );

        // Calculate the master key.
        logger.trc( "masterKey: scrypt( masterPassword, masterKeySalt, N=%d, r=%d, p=%d )",
                    scrypt_N(), scrypt_r(), scrypt_p() );
        byte[] masterPasswordBytes = toBytes( masterPassword );
        byte[] masterKey           = scrypt( masterKeySalt, masterPasswordBytes );
        Arrays.fill( masterKeySalt, (byte) 0 );
        Arrays.fill( masterPasswordBytes, (byte) 0 );
        logger.trc( "  => masterKey.id: %s", CodeUtils.encodeHex( toID( masterKey ) ) );

        return masterKey;
    }

    protected byte[] scrypt(final byte[] masterKeySalt, final byte[] mpBytes) {
        try {
            return SCrypt.scrypt( mpBytes, masterKeySalt, scrypt_N(), scrypt_r(), scrypt_p(), mpw_dkLen() );
        }
        catch (final GeneralSecurityException e) {
            throw logger.bug( e );
        }
    }

    @Override
    public byte[] siteKey(final byte[] masterKey, final String siteName, UnsignedInteger siteCounter, final MPKeyPurpose keyPurpose,
                          @Nullable final String keyContext) {

        String keyScope = keyPurpose.getScope();
        logger.trc( "keyScope: %s", keyScope );

        // OTP counter value.
        if (siteCounter.longValue() == 0)
            siteCounter = UnsignedInteger.valueOf( (System.currentTimeMillis() / (mpw_otp_window() * 1000)) * mpw_otp_window() );

        // Calculate the site seed.
        byte[] siteNameBytes         = siteName.getBytes( mpw_charset() );
        byte[] siteNameLengthBytes   = toBytes( siteName.length() );
        byte[] siteCounterBytes      = toBytes( siteCounter );
        byte[] keyContextBytes       = ((keyContext == null) || keyContext.isEmpty())? null: keyContext.getBytes( mpw_charset() );
        byte[] keyContextLengthBytes = (keyContextBytes == null)? null: toBytes( keyContextBytes.length );
        logger.trc( "siteSalt: keyScope=%s | #siteName=%s | siteName=%s | siteCounter=%s | #keyContext=%s | keyContext=%s",
                    keyScope, CodeUtils.encodeHex( siteNameLengthBytes ), siteName, CodeUtils.encodeHex( siteCounterBytes ),
                    (keyContextLengthBytes == null)? null: CodeUtils.encodeHex( keyContextLengthBytes ), keyContext );

        byte[] sitePasswordInfo = Bytes.concat( keyScope.getBytes( mpw_charset() ), siteNameLengthBytes, siteNameBytes, siteCounterBytes );
        if (keyContextBytes != null)
            sitePasswordInfo = Bytes.concat( sitePasswordInfo, keyContextLengthBytes, keyContextBytes );
        logger.trc( "  => siteSalt.id: %s", CodeUtils.encodeHex( toID( sitePasswordInfo ) ) );

        logger.trc( "siteKey: hmac-sha256( masterKey.id=%s, siteSalt )", CodeUtils.encodeHex( toID( masterKey ) ) );
        byte[] sitePasswordSeedBytes = mpw_digest().of( masterKey, sitePasswordInfo );
        logger.trc( "  => siteKey.id: %s", CodeUtils.encodeHex( toID( sitePasswordSeedBytes ) ) );

        return sitePasswordSeedBytes;
    }

    @Override
    public String siteResult(final byte[] masterKey, final byte[] siteKey, final String siteName, final UnsignedInteger siteCounter,
                             final MPKeyPurpose keyPurpose,
                             @Nullable final String keyContext, final MPResultType resultType, @Nullable final String resultParam) {

        switch (resultType.getTypeClass()) {
            case Template:
                return sitePasswordFromTemplate( masterKey, siteKey, resultType, resultParam );
            case Stateful:
                return sitePasswordFromCrypt( masterKey, siteKey, resultType, resultParam );
            case Derive:
                return sitePasswordFromDerive( masterKey, siteKey, resultType, resultParam );
        }

        throw logger.bug( "Unsupported result type class: %s", resultType.getTypeClass() );
    }

    @Override
    public String sitePasswordFromTemplate(final byte[] masterKey, final byte[] siteKey, final MPResultType resultType,
                                           @Nullable final String resultParam) {

        int[] _siteKey = new int[siteKey.length];
        for (int i = 0; i < siteKey.length; ++i) {
            ByteBuffer buf = ByteBuffer.allocate( Integer.SIZE / Byte.SIZE ).order( mpw_byteOrder() );
            Arrays.fill( buf.array(), (byte) ((siteKey[i] > 0)? 0x00: 0xFF) );
            buf.position( 2 );
            buf.put( siteKey[i] ).rewind();
            _siteKey[i] = buf.getInt() & 0xFFFF;
        }

        // Determine the template.
        Preconditions.checkState( _siteKey.length > 0 );
        int        templateIndex = _siteKey[0];
        MPTemplate template      = resultType.getTemplateAtRollingIndex( templateIndex );
        logger.trc( "template: %d => %s", templateIndex, template.getTemplateString() );

        // Encode the password from the seed using the template.
        StringBuilder password = new StringBuilder( template.length() );
        for (int i = 0; i < template.length(); ++i) {
            int                      characterIndex    = _siteKey[i + 1];
            MPTemplateCharacterClass characterClass    = template.getCharacterClassAtIndex( i );
            char                     passwordCharacter = characterClass.getCharacterAtRollingIndex( characterIndex );
            logger.trc( "  - class: %c, index: %5d (0x%2H) => character: %c",
                        characterClass.getIdentifier(), characterIndex, _siteKey[i + 1], passwordCharacter );

            password.append( passwordCharacter );
        }
        logger.trc( "  => password: %s", password );

        return password.toString();
    }

    @Override
    public String sitePasswordFromCrypt(final byte[] masterKey, final byte[] siteKey, final MPResultType resultType,
                                        @Nullable final String resultParam) {

        Preconditions.checkNotNull( resultParam );
        Preconditions.checkArgument( !resultParam.isEmpty() );

        try {
            // Base64-decode
            byte[] cipherBuf = CryptUtils.decodeBase64( resultParam );
            logger.trc( "b64 decoded: %d bytes = %s", cipherBuf.length, CodeUtils.encodeHex( cipherBuf ) );

            // Decrypt
            byte[] plainBuf  = CryptUtils.decrypt( cipherBuf, masterKey, true );
            String plainText = mpw_charset().decode( ByteBuffer.wrap( plainBuf ) ).toString();
            logger.trc( "decrypted -> plainText: %d bytes = %s = %s", plainBuf.length, plainText, CodeUtils.encodeHex( plainBuf ) );

            return plainText;
        }
        catch (final BadPaddingException e) {
            throw Throwables.propagate( e );
        }
    }

    @Override
    public String sitePasswordFromDerive(final byte[] masterKey, final byte[] siteKey, final MPResultType resultType,
                                         @Nullable final String resultParam) {

        if (resultType == MPResultType.DeriveKey) {
            int resultParamInt = ConversionUtils.toIntegerNN( resultParam );
            if (resultParamInt == 0)
                resultParamInt = mpw_keySize_max();
            if ((resultParamInt < mpw_keySize_min()) || (resultParamInt > mpw_keySize_max()) || ((resultParamInt % 8) != 0))
                throw logger.bug( "Parameter is not a valid key size (should be 128 - 512): %s", resultParam );
            int keySize = resultParamInt / 8;
            logger.trc( "keySize: %d", keySize );

            // Derive key
            byte[] resultKey = null; // TODO: mpw_kdf_blake2b()( keySize, siteKey, MPSiteKeySize, NULL, 0, 0, NULL );
            if (resultKey == null)
                throw logger.bug( "Could not derive result key." );

            // Base64-encode
            String b64Key = Preconditions.checkNotNull( CryptUtils.encodeBase64( resultKey ) );
            logger.trc( "b64 encoded -> key: %s", b64Key );

            return b64Key;
        } else
            throw logger.bug( "Unsupported derived password type: %s", resultType );
    }

    @Override
    public String siteState(final byte[] masterKey, final byte[] siteKey, final String siteName, final UnsignedInteger siteCounter,
                            final MPKeyPurpose keyPurpose,
                            @Nullable final String keyContext, final MPResultType resultType, final String resultParam) {

        try {
            // Encrypt
            byte[] cipherBuf = CryptUtils.encrypt( resultParam.getBytes( mpw_charset() ), masterKey, true );
            logger.trc( "cipherBuf: %d bytes = %s", cipherBuf.length, CodeUtils.encodeHex( cipherBuf ) );

            // Base64-encode
            String cipherText = Preconditions.checkNotNull( CryptUtils.encodeBase64( cipherBuf ) );
            logger.trc( "b64 encoded -> cipherText: %s", cipherText );

            return cipherText;
        }
        catch (final IllegalBlockSizeException e) {
            throw logger.bug( e );
        }
    }

    // Configuration

    @Override
    public MPMasterKey.Version version() {
        return MPMasterKey.Version.V0;
    }

    /**
     * mpw: defaults: password result type.
     */
    @Override
    public MPResultType mpw_default_type() {
        return MPResultType.GeneratedLong;
    }

    /**
     * mpw: defaults: initial counter value.
     */
    @Override
    public UnsignedInteger mpw_default_counter() {
        return UnsignedInteger.ONE;
    }

    /**
     * mpw: validity for the time-based rolling counter.
     */
    @Override
    @SuppressWarnings("MagicNumber")
    public long mpw_otp_window() {
        return 5 * 60 /* s */;
    }

    /**
     * mpw: Key ID hash.
     */
    @Override
    public MessageDigests mpw_hash() {
        return MessageDigests.SHA256;
    }

    /**
     * mpw: Site digest.
     */
    @Override
    public MessageAuthenticationDigests mpw_digest() {
        return MessageAuthenticationDigests.HmacSHA256;
    }

    /**
     * mpw: Platform-agnostic byte order.
     */
    @Override
    public ByteOrder mpw_byteOrder() {
        return ByteOrder.BIG_ENDIAN;
    }

    /**
     * mpw: Input character encoding.
     */
    @Override
    public Charset mpw_charset() {
        return Charsets.UTF_8;
    }

    /**
     * mpw: Master key size (byte).
     */
    @Override
    @SuppressWarnings("MagicNumber")
    public int mpw_dkLen() {
        return 64;
    }

    /**
     * mpw: Minimum size for derived keys (bit).
     */
    @Override
    @SuppressWarnings("MagicNumber")
    public int mpw_keySize_min() {
        return 128;
    }

    /**
     * mpw: Maximum size for derived keys (bit).
     */
    @Override
    @SuppressWarnings("MagicNumber")
    public int mpw_keySize_max() {
        return 512;
    }

    /**
     * scrypt: Parallelization parameter.
     */
    @Override
    @SuppressWarnings("MagicNumber")
    public int scrypt_p() {
        return 2;
    }

    /**
     * scrypt: Memory cost parameter.
     */
    @Override
    @SuppressWarnings("MagicNumber")
    public int scrypt_r() {
        return 8;
    }

    /**
     * scrypt: CPU cost parameter.
     */
    @Override
    @SuppressWarnings("MagicNumber")
    public int scrypt_N() {
        return 32768;
    }

    // Utilities

    @Override
    public byte[] toBytes(final int number) {
        return ByteBuffer.allocate( Integer.SIZE / Byte.SIZE ).order( mpw_byteOrder() ).putInt( number ).array();
    }

    @Override
    public byte[] toBytes(final UnsignedInteger number) {
        return ByteBuffer.allocate( Integer.SIZE / Byte.SIZE ).order( mpw_byteOrder() ).putInt( number.intValue() ).array();
    }

    @Override
    public byte[] toBytes(final char[] characters) {
        ByteBuffer byteBuffer = mpw_charset().encode( CharBuffer.wrap( characters ) );

        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get( bytes );

        Arrays.fill( byteBuffer.array(), (byte) 0 );
        return bytes;
    }

    @Override
    public byte[] toID(final byte[] bytes) {
        return mpw_hash().of( bytes );
    }
}
