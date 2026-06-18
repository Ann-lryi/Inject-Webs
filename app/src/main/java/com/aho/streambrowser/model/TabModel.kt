package com.aho.streambrowser.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class TabModel(
    val id:      String = UUID.randomUUID().toString(),
    var url:     String = "about:blank",
    var title:   String = "New Tab",
    var scrollY: Int    = 0
) : Parcelable
