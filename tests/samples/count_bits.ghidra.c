uint FUN_00101189(uint param_1)

{
  uint local_10;
  uint local_c;

  local_c = 0;
  for (local_10 = param_1; local_10 != 0; local_10 = local_10 >> 1) {
    local_c = local_c + (local_10 & 1);
  }
  return local_c;
}
