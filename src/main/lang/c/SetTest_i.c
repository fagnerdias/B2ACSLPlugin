/* WARNING if type checker is not performed, translation could contain errors ! */

#include "SetTest.h"

/* Clause CONCRETE_CONSTANTS */
/* Basic constants */

#define SetTest__MM 10
/* Array and record constants */
/* Clause CONCRETE_VARIABLES */

static int32_t SetTest__ss_i;
/* Clause INITIALISATION */
void SetTest__INITIALISATION(void)
{
    
    SetTest__ss_i = 0;
}

/* Clause OPERATIONS */

void SetTest__Add(int32_t xx)
{
    SetTest__ss_i++;
}

void SetTest__count(int32_t *rr)
{
    (*rr) = SetTest__ss_i;
}

