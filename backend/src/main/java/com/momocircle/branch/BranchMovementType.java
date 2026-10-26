package com.momocircle.branch;

/** Kinds of branch stock movement; the database CHECK (ck_branch_move_shape) enforces the from/to shape of each. */
public enum BranchMovementType { ALLOCATE, RETURN_TO_CENTRAL, TRANSFER, SALE_RESERVE, SALE_RELEASE }
