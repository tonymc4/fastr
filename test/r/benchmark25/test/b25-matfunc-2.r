# extracted from R Benchmark 2.5 (06/2008) [Simon Urbanek]
# http://r.research.att.com/benchmarks/R-benchmark-25.R

# II. Matrix functions
# Eigenvalues of a 600x600 random matrix

# CTK: to ensure repeatability, this is the seed used by libRmath
.Random.seed = c(401L,1234L,5678L)

b25matfunc <- function() {
  a <- array(rnorm(60*60), dim = c(60, 60))
  b <- eigen(a, symmetric=FALSE, only.values=TRUE)$values
      # the 2.5 version of the benchmark uses $Value instead of $values but that is not working with R

  # CTK: to ensure materialization, and to get a result to check
  round( sum(b), digits=5 )
}

# CTK: to make GNU-R print all 5 decimal digits
options(digits=15)

b25matfunc()
