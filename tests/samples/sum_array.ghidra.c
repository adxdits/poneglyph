long FUN_00101149(long param_1,int param_2)

{
  int local_14;
  long local_10;

  local_10 = 0;
  for (local_14 = 0; local_14 < param_2; local_14 = local_14 + 1) {
    local_10 = local_10 + (long)*(int *)(param_1 + (long)local_14 * 4);
  }
  return local_10;
}
