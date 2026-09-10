package kr.safecross.mobile.domain.model

data class DestinationItem(
    val id: String,
    val name: String,
    val address: String,
    val location: LocationPoint,
    val isFavorite: Boolean = false
)
