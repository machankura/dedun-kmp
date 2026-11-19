package com.machankura.dedun.compose.managers

/**
 * One group state blob, written by the engine the app shipped before it moved to
 * marmot-kmp, with the two facts a test needs in order to know it is still that blob.
 *
 * It sits here rather than inside the test that first used it because two tests now
 * depend on these bytes being *old* ones: [MlsGroupStateCompatibilityTest], which
 * decodes them with mls-kmp, and `ShippedProfileUpgradeJvmTest`, which puts them in a
 * v36 database and opens that database with this branch. A blob copied into two files
 * can be regenerated in one of them, and the copy that was left alone would then be
 * the only evidence, with nothing on either saying which was which.
 *
 * Regenerate with the project in docs/tools/old-state-blob, whose only dependency is
 * `com.vitorpamplona.quartz:quartz:1.15.2` -- never with the engine the app now
 * ships, which would leave every test over it passing and measuring nothing.
 *
 * docs/marmot-kmp.md, Phases 4 and 9.
 */
internal object OldEngineBlob {
    /** What quartz 1.15.2's `MlsGroupState.STATE_VERSION` was. */
    const val STATE_VERSION = 4

    /** The `nostr_group_id` in the fixture's MIP-01 extension, and the room's id. */
    const val ROOM_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    /**
     * `MlsGroup.create(identity, null, listOf(MarmotGroupData.bootstrap(...).toExtension()))`
     * under quartz 1.15.2, saved and TLS-encoded: a legacy MIP-01 group at epoch 0,
     * which is the shape every room in the wild started as.
     */
    const val HEX =
        "00040001000120c3c9d291c98d1f3f01dabcdcf2abf99980ae06e9631539e4f7e5b547ad2c03dc000000000000000020b675" +
        "ae4d9e4062d1dafbf3d3b4dc67e98063def6dbda2008f60573c200ae6d5b0040a600030902f2ee02000a020001f2ee409600" +
        "02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa07466978747572652d41206c6567616379" +
        "204d49502d303120726f6f6d207772697474656e2062792071756172747a20312e31352e322000749390d99f65603651fbf8" +
        "baf7071ad63ee5660984958d1dd7a5821c92b52a18177773733a2f2f72656c61792e6578616d706c652e636f6d0000000040" +
        "cc40ca010120b9da1a21fffe43be2a5e2b83eddc853f71f6b57fae67e017be8dbfb1778ce95f209b4ebc94792d7360f4017d" +
        "45cbd845e5f488e4962754239af9d48de50ea5224900012000749390d99f65603651fbf8baf7071ad63ee5660984958d1dd7" +
        "a5821c92b52a02000102000102f2ee02000a02000101000000006afe12c0000000006b6cd0c0004040ca7ad7d4a33a7cbde1" +
        "34f8225938ea6a1e65cd06379b71abb00ab48fe17935712db23a56630d92c7c54cea6a3eb62326a3228e7464820518e9cda1" +
        "923d50a80000000000209e5c0c64ef40fead8259db9426c4af2bec91f750c5a0b6c767ccc431cf6e2c412087f4d7255d2a51" +
        "26cb5c0a8d794a335f0d0032e66555546904405be0f216b371208ae2411f07faac6dae2f701e5c6e8a7e36255f7dc91565e5" +
        "faaf58b74905b80d2041e6e1452c80b6d6ed44e1a3d320478af46daa55a05e4b5edf833b28219d4730200e4f86c223b2113e" +
        "0691b2094155e2623fdf6030e08f293cd05da34122c62b5420337d8cf084bab81304c588ebbb8309571165cd0f1b6ddfdfbd" +
        "bd0a0d7df7998c20528a63ffcf11b677324646bfb478b89a896cc40105e5ff91b9d23a9a1e4d4fba2096d23998acca0e6575" +
        "649594539dee4101ddd9a5e9e9d9995db290eabae7878e204888d7a4c9cacfedec6299d6a503055615e5824cc09e90ffe865" +
        "cbd497310b1320c25e0955cf5a866ae44b5f698569fa427455e4a74187f69b4fc9d3e4badceaea2052371f31ed9069072037" +
        "b2ea4ea312f34adb27601ece5a6fee2968fb8656e9522074a9d547eaf3b009f58a0b647cbee2947800db888f4e9fa3d9c9ad" +
        "d7460d03de2074a9d547eaf3b009f58a0b647cbee2947800db888f4e9fa3d9c9add7460d03de4040331d4cde98587d393b9b" +
        "d906ad826fd40e0ec3bd33e023aac8c223bd7c6cba859b4ebc94792d7360f4017d45cbd845e5f488e4962754239af9d48de5" +
        "0ea5224920df018d4154497d07c9363f4ada26d4a68d0eb7f77ccd837ccb6540b76148571700200e4f86c223b2113e0691b2" +
        "094155e2623fdf6030e08f293cd05da34122c62b54000000000000000000000000"

    /** The first two bytes of [HEX], which are the state version. */
    fun stateVersionOf(bytes: ByteArray): Int = (bytes[0].toInt() and 0xff shl 8) or (bytes[1].toInt() and 0xff)
}
