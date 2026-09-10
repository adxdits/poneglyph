void FUN_001011b6(char *param_1)

{
  size_t sVar1;
  char cVar2;
  int local_18;
  int local_14;

  sVar1 = strlen(param_1);
  local_18 = 0;
  local_14 = (int)sVar1 + -1;
  while (local_18 < local_14) {
    cVar2 = param_1[local_18];
    param_1[local_18] = param_1[local_14];
    param_1[local_14] = cVar2;
    local_18 = local_18 + 1;
    local_14 = local_14 + -1;
  }
  return;
}
