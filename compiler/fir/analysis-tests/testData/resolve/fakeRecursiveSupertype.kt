import incorrect.directory.My

open class My : <!CYCLIC_INHERITANCE_HIERARCHY!>My<!>()

open class Your : His()

open class His : <!CYCLIC_INHERITANCE_HIERARCHY!>Your<!>()
