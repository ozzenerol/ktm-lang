import test;

pub void quickSort(i64 *arr, i64 hi, i64 lo=0) {
    if (lo < hi) {
        i64 pivot = arr[hi], idx = lo, i = lo;
        while (i < hi) {
            if (arr[i] <= pivot) {
                i64 tmp = arr[i];
                arr[i] = arr[idx];
                arr[idx] = tmp;
                ++idx;
            }
            ++i;
        }
        arr[hi] = arr[idx];
        arr[idx] = pivot;
        quickSort(arr,idx-1,lo);
        quickSort(arr,hi,idx+1);
    }
}

i32 runMain() {
    printf("Test - qsort, recursion and default function params: ");
    i64 correct = 0;
    i64 nums[10] = {11,1,2,4,0,5,6,7,8,3};    
    i64 expected[10] = {0,1,2,3,4,5,6,7,8,11};
    quickSort(nums,10-1);

    for (i64 i = 0; i < 10; ++i) {
        if (nums[i] != expected[i]) {
            break;
        } 
        correct++;
    }
    test.printResult(correct == 10, 1);
    printf("====\n");
    return 0;
}

void main() {
    runMain();
}
