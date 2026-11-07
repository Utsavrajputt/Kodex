package com.invictus.kodex.github.api

/** Repository public key used to seal Actions secrets. */
data class GhPublicKey(val keyId: String, /** base64 */ val key: String)

/** A repository that was just created for the signed-in user. */
data class GhNewRepo(val owner: String, val name: String, val cloneUrl: String, val defaultBranch: String)
