package com.iamxpp.isaver.ui
import com.iamxpp.isaver.locations.*
sealed interface LocationAvailability{data object Checking:LocationAvailability;data class Available(val readable:Boolean,val writable:Boolean):LocationAvailability;data class Unavailable(val reason:String):LocationAvailability}
data class CustomLocationState(val location:StorageLocation.Direct,val availability:LocationAvailability)
data class LocationHomeUiState(val loading:Boolean=true,val commonLocations:List<StorageLocation.Direct> = emptyList(),val appGroups:List<ResolvedAppLocation> = emptyList(),val customLocations:List<CustomLocationState> = emptyList(),val recentLocations:List<StorageLocation.Direct> = emptyList(),val error:String?=null,val addError:String?=null,val operationInProgress:Boolean=false,val saveSuccessVersion:Long=0)
