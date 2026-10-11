#include "testhelper.ktm"

import json, mem, str;

i32 main() {
    printf("Test - JSON parsing: ");
    i32 tests = 10, correct = 0;
    u8 *raw = "{
    \"hello\": 10,
    \"key\": \"some string\",
    \"id\": null,
    \"bool2\": false,
    \"bool\": true,
    \"array\": [1,   30030303  ,   430  ,

    -14]
  }";

    auto j = json.parse(raw);
    if (j != null) { correct++; }

    auto obj = json.select(j,".hello:i");
    if (obj) {
        if (obj.intVal == 10) { correct++; }
    }

    u8 *str = json.toStr(j);
    printf("%s\n",str);
    mem.free(str);

    if ((obj = json.select(j,".key:s"))) {
        if (!str.cmpLen(obj.str,"some string",11)) { correct++; }
    }

    if ((obj = json.select(j,".id"))) {
        if (obj.type == json.NULL) { correct++; }
    }

    if ((obj = json.select(j,".bool"))) {
        if (obj.boolean == true) { correct++; }
    }

    if ((obj = json.select(j,".bool2"))) {
        if (obj.boolean == false) { correct++; }
    }

    if ((obj = json.select(j,".array[0]:i"))) {
        if (obj.intVal == 1) { correct++; }
    }
    if ((obj = json.select(j,".array[1]:i"))) {
        if (obj.intVal == 30030303) { correct++; }
    }
    if ((obj = json.select(j,".array[2]"))) {
        if (obj.intVal == 430) { correct++; }
    }
    if ((obj = json.select(j,".array[3]:i"))) {
        if (obj.intVal == -14) { correct++; }
    }

    json.release(j);
    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
